package com.linli.service.impl;

import com.linli.common.ApiException;
import com.linli.common.ErrorCode;
import com.linli.common.PageResult;
import com.linli.constant.RedisKeyConstant;
import com.linli.dto.CouponQueryDTO;
import com.linli.entity.Coupon;
import com.linli.entity.UserCoupon;
import com.linli.mapper.CouponMapper;
import com.linli.mapper.UserCouponMapper;
import com.linli.service.CouponService;
import com.linli.vo.CouponVO;
import com.linli.vo.MyCouponVO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 优惠券。领取走"Redis 挡量 + 数据库保正确"两层：
 *
 * 1. Lua 脚本在 Redis 单线程里原子完成"判库存 + 判重 + 预扣 + 登记"，绝大多数重复领和超卖在这里就被挡掉，
 *    不用打到数据库。
 * 2. 数据库侧仍然是权威：{@code update coupon set stock=stock-1 where id=? and stock>0} 的乐观锁防超卖，
 *    {@code uk_coupon_user} 唯一索引防一人多领。任何一步失败都要把 Redis 的预扣归还，否则两边会越差越多。
 *
 * Redis 的库存键可以随时从数据库重建（见 warmUp），所以 Redis 重启或键被清都不会丢正确性，只会短暂退化成"全靠数据库判"。
 */
@Service
public class CouponServiceImpl implements CouponService {

    private static final Logger log = LoggerFactory.getLogger(CouponServiceImpl.class);

    private static final int GATE_OK = 0;
    private static final int GATE_SOLD_OUT = 1;
    private static final int GATE_DUPLICATE = 2;
    private static final int GATE_UNINITIALIZED = 3;

    /**
     * 注意别用 {@code new DefaultRedisScript<>("lua/xxx.lua", Long.class)} —— 那个重载把第一个参数
     * 当成**脚本文本**，Redis 会去编译字符串本身，报 {@code user_script:1: '=' expected near '/'}。
     * 要走 classpath 文件必须用 setLocation（和黑马点评的写法一致）。
     */
    private static final RedisScript<Long> CLAIM_SCRIPT = claimScript();

    private static RedisScript<Long> claimScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/claim_coupon.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;
    private final StringRedisTemplate redis;

    public CouponServiceImpl(CouponMapper couponMapper, UserCouponMapper userCouponMapper,
                             StringRedisTemplate redis) {
        this.couponMapper = couponMapper;
        this.userCouponMapper = userCouponMapper;
        this.redis = redis;
    }

    @Override
    public PageResult<CouponVO> page(CouponQueryDTO query) {
        CouponQueryDTO condition = query == null ? new CouponQueryDTO() : query;
        long total = couponMapper.countQuery(condition);
        List<CouponVO> list = total == 0 ? List.of()
                : couponMapper.pageQuery(condition).stream().map(CouponVO::from).toList();
        return new PageResult<>(list, total, condition.normalizedPage(), condition.normalizedPageSize());
    }

    @Override
    public List<CouponVO> listByShop(Long shopId, Long userId) {
        if (shopId == null) return List.of();
        return couponMapper.listByShop(shopId, userId).stream().map(CouponVO::from).toList();
    }

    @Override
    public List<MyCouponVO> my(Long userId, String status) {
        return userCouponMapper.listByUser(userId, statusCode(status)).stream().map(MyCouponVO::from).toList();
    }

    /** 契约用 AVAILABLE/USED/EXPIRED 三个词，库里是 1/2/3，翻译只发生在这里。 */
    private static Integer statusCode(String status) {
        if (status == null || status.isBlank()) return null;
        return switch (status.trim().toUpperCase()) {
            case "AVAILABLE" -> UserCoupon.STATUS_UNUSED;
            case "USED" -> UserCoupon.STATUS_USED;
            case "EXPIRED" -> UserCoupon.STATUS_EXPIRED;
            default -> throw new ApiException(ErrorCode.PARAM_INVALID, "status 只支持 AVAILABLE/USED/EXPIRED");
        };
    }

    @Override
    @Transactional
    public MyCouponVO claim(Long couponId, Long userId) {
        if (couponId == null) throw new ApiException(ErrorCode.PARAM_INVALID, "缺少优惠券 id");
        Coupon coupon = couponMapper.getById(couponId);
        if (coupon == null) throw new ApiException(ErrorCode.NOT_FOUND, "优惠券不存在");
        if (!coupon.receivableNow(LocalDateTime.now())) {
            throw new ApiException(ErrorCode.CONFLICT, "该券已下架或不在领取时间窗口内");
        }

        // 闸门单独接一层，因为它的失败和下面那一层的失败性质不同：
        // 走到下面时"Redis 已经扣了"是既定事实，补还即可；而这里失败时**扣没扣是未知的**，
        // 猜错任一方向都会出事——见 discardGateCache 的注释。
        try {
            passRedisGate(coupon, userId);
        } catch (ApiException e) {
            // 抢光 / 已领过 / 不在窗口 / 键预热失败：这几种都是**明确结果**，缓存里的数是对的，别动它
            throw e;
        } catch (RuntimeException e) {
            discardGateCache(couponId, userId);
            throw e;
        }
        try {
            if (couponMapper.deductStock(couponId) == 0) {
                throw new ApiException(ErrorCode.CONFLICT, "券已抢光");
            }
            UserCoupon record = snapshot(coupon, userId);
            userCouponMapper.insert(record);
            log.info("领券成功: couponId={} userId={} userCouponId={}", couponId, userId, record.getId());
            return MyCouponVO.from(record);
        } catch (DuplicateKeyException e) {
            compensate(couponId, userId);
            throw new ApiException(ErrorCode.CONFLICT, "你已经领取过这张券");
        } catch (ApiException e) {
            compensate(couponId, userId);
            throw e;
        } catch (RuntimeException e) {
            // 语句超时、连接池拿不到、MySQL 挂了——这些都从 DataAccessException 系穿过来，
            // 上面两支接不住。**接不住就等于把 Redis 那一笔预扣永久留下**：库存键的 TTL 跟到券的
            // end_time，这期间所有人都少领一张，而数据库里明明还有货。
            // 这不是推演：2026-10-28 用行锁把 deductStock 卡到 10 秒超时，实测失败后
            // Redis=3 / DB=4，再放三个人领就变成 redis=0 / db=1，第四个人拿到「券已抢光」——
            // 见 WORKLOG S-122。补偿必须覆盖"任何没成功的运行时异常"，而不是只覆盖我想到那两种。
            // 摘掉这一支，CouponServiceImplTest 里那两条 queryTimeout* 用例会立刻红（做过反向对照）。
            compensate(couponId, userId);
            throw e;
        }
    }

    /** 第一层：Redis 原子预检 + 预扣。未初始化时从数据库预热一次再重试。 */
    private void passRedisGate(Coupon coupon, Long userId) {
        Long code = executeGate(coupon, userId);
        if (code != null && code == GATE_UNINITIALIZED) {
            warmUp(coupon);
            code = executeGate(coupon, userId);
        }
        if (code == null) {
            // 脚本跑完却没拿到返回值：这一笔扣没扣不知道。所以先按"未知态"弃掉库存键，
            // 再抛业务错（对外仍是 50001，用户看到的文案不变）。
            discardGateCache(coupon.getId(), userId);
            throw new ApiException(ErrorCode.SERVER_ERROR, "领券服务暂时不可用，请稍后重试");
        }
        switch (code.intValue()) {
            case GATE_OK -> { }
            case GATE_SOLD_OUT -> throw new ApiException(ErrorCode.CONFLICT, "券已抢光");
            case GATE_DUPLICATE -> throw new ApiException(ErrorCode.CONFLICT, "你已经领取过这张券");
            default -> throw new ApiException(ErrorCode.SERVER_ERROR, "领券服务暂时不可用，请稍后重试");
        }
    }

    private Long executeGate(Coupon coupon, Long userId) {
        return redis.execute(CLAIM_SCRIPT,
                List.of(RedisKeyConstant.couponStock(coupon.getId()), RedisKeyConstant.couponClaimed(coupon.getId())),
                String.valueOf(userId),
                String.valueOf(ttlUntilExpireSeconds(coupon.getEndTime())));
    }

    /**
     * 用数据库把两个键重建出来：库存取 coupon.stock，已领人员从 user_coupon 反查。
     * 这一步决定"Redis 丢了会不会算错"——答案是不会，只会短暂失去快速拦截。
     */
    private void warmUp(Coupon coupon) {
        String stockKey = RedisKeyConstant.couponStock(coupon.getId());
        String claimedKey = RedisKeyConstant.couponClaimed(coupon.getId());
        int stock = coupon.getStock() == null ? 0 : coupon.getStock();
        Duration ttl = Duration.ofSeconds(ttlUntilExpireSeconds(coupon.getEndTime()));
        redis.opsForValue().set(stockKey, String.valueOf(stock), ttl);
        List<Long> claimers = couponMapper.listClaimerIds(coupon.getId());
        if (!claimers.isEmpty()) {
            redis.opsForSet().add(claimedKey, claimers.stream().map(String::valueOf).toArray(String[]::new));
            redis.expire(claimedKey, ttl);
        }
        log.info("券键已预热: couponId={} stock={} 已领人数={}", coupon.getId(), stock, claimers.size());
    }

    /** 数据库或唯一索引拒绝了这一单，把 Redis 的预扣还回去，否则两边会越差越多。 */
    private void compensate(Long couponId, Long userId) {
        try {
            redis.opsForSet().remove(RedisKeyConstant.couponClaimed(couponId), String.valueOf(userId));
            String stockKey = RedisKeyConstant.couponStock(couponId);
            // 只在键存在时归还；键已经不在了还 increment，会凭空造出一个"有库存"来
            if (Boolean.TRUE.equals(redis.hasKey(stockKey))) {
                redis.opsForValue().increment(stockKey);
            }
        } catch (RuntimeException e) {
            log.warn("领券补偿失败，Redis 预扣未归还: couponId={} userId={}", couponId, userId, e);
        }
    }

    /**
     * 预扣段"状态未知"时的处理：**不猜方向，回到真相源**。
     *
     * <p>为什么不是 {@link #compensate}：那个方法的语义是"我知道扣成功了，现在要还回去"，所以它 increment。
     * 而超时 / 断连时我们并不知道脚本执行了没有——如果没执行还 increment，就凭空造出一张库存，
     * 从"少卖"变成"超卖"，比原缺陷更难查（少卖只是少赚，超卖要赔券）。
     *
     * <p>所以这里做两件事：
     * ① 删掉库存键 —— 下一次领取由 {@link #warmUp} 按"数据库库存 - 已售"重建，真相只认数据库；
     * ② 只摘掉**自己**那一条判重记录 —— 脚本可能已经把自己 sadd 进去了，而库里并没有这行，
     *    不摘的话这个用户会被自己挡住，之后一直看到"你已经领取过"，等于这张券对他消失了。
     *
     * <p>已知代价：弃键后并发进来的几个请求会各自触发一次预热，把 Redis 的库存重设成数据库真值。
     * 那最多让多出来的请求穿过闸门、在数据库 {@code where stock>0} 上被判"抢光"，
     * 正确性不受影响（数据库始终是权威），只是 Redis 需要重新预热一次。
     */
    private void discardGateCache(Long couponId, Long userId) {
        try {
            redis.delete(RedisKeyConstant.couponStock(couponId));
            redis.opsForSet().remove(RedisKeyConstant.couponClaimed(couponId), String.valueOf(userId));
            log.warn("领券预扣状态未知，已弃用库存键（下次领取从数据库重建）: couponId={} userId={}", couponId, userId);
        } catch (RuntimeException e) {
            // 连删除都失败说明 Redis 整体不可用：不抛，否则用户看到的会是"清理失败"而不是原始故障；
            // 库存键本身有 TTL（跟到券到期），下一次领取也会因为键不存在而走预热。
            log.warn("领券预扣状态未知且清理失败（下次领取会因键缺失自动重建）: couponId={} userId={}", couponId, userId, e);
        }
    }

    private UserCoupon snapshot(Coupon coupon, Long userId) {
        UserCoupon record = new UserCoupon();
        record.setUserId(userId);
        record.setCouponId(coupon.getId());
        record.setShopId(coupon.getShopId());
        record.setName(coupon.getName());
        record.setAmount(coupon.getAmount());
        record.setThresholdAmount(coupon.getThresholdAmount());
        record.setStatus(UserCoupon.STATUS_UNUSED);
        record.setReceiveTime(LocalDateTime.now());
        record.setExpireTime(coupon.getEndTime());
        return record;
    }

    /** 券到期之后再留一段余量，避免"刚过期就查不到状态"；拿不到到期时间时退化成固定余量。 */
    private static long ttlUntilExpireSeconds(LocalDateTime end) {
        long buffer = RedisKeyConstant.COUPON_KEY_TTL_BUFFER_SECONDS;
        if (end == null) return buffer;
        long remaining = Duration.between(LocalDateTime.now(), end).getSeconds();
        return Math.max(remaining + buffer, 60);
    }
}
