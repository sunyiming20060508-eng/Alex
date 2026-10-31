package com.linli.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linli.common.ApiException;
import com.linli.constant.RedisKeyConstant;
import com.linli.entity.Coupon;
import com.linli.entity.UserCoupon;
import com.linli.mapper.CouponMapper;
import com.linli.mapper.UserCouponMapper;
import com.linli.vo.MyCouponVO;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 领券的单元测试。这里测的是"两层防线的配合"：Lua 返回值 0/1/2/3 各走哪条路、
 * 数据库拒绝时 Redis 的预扣有没有还回去、键丢了能不能从数据库重建。
 *
 * 并发不超卖本身由接口压测证明（10 并发 5 库存那轮），单测负责的是分支覆盖——
 * 尤其是"补偿"这种只在失败时才走、压测里很难定点观察的路径。
 */
@ExtendWith(MockitoExtension.class)
class CouponServiceImplTest {

    private static final Long USER_ID = 4L;
    private static final Long COUPON_ID = 2L;
    private static final String STOCK_KEY = RedisKeyConstant.couponStock(COUPON_ID);
    private static final String CLAIMED_KEY = RedisKeyConstant.couponClaimed(COUPON_ID);

    @Mock private CouponMapper couponMapper;
    @Mock private UserCouponMapper userCouponMapper;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;
    @Mock private SetOperations<String, String> setOps;

    private CouponServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CouponServiceImpl(couponMapper, userCouponMapper, redis);
    }

    @Test
    @DisplayName("缺 id、券不存在、已下架或不在窗口：都拦在 Redis 之前")
    void rejectsBeforeTouchingRedis() {
        assertFails(() -> service.claim(null, USER_ID), 40001, "缺少优惠券 id");

        when(couponMapper.getById(COUPON_ID)).thenReturn(null);
        assertFails(() -> service.claim(COUPON_ID, USER_ID), 40401, "优惠券不存在");

        Coupon offShelf = coupon("50.00", "100.00", 10, 0);
        when(couponMapper.getById(COUPON_ID)).thenReturn(offShelf);
        assertFails(() -> service.claim(COUPON_ID, USER_ID), 40901, "已下架或不在领取时间窗口内");
    }

    @Test
    @DisplayName("Lua 返回 1=售罄、2=重复领：直接拒，一次数据库写都不发生")
    void gateBlocksSoldOutAndDuplicate() {
        givenCoupon(coupon("50.00", "100.00", 10, 1));

        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);
        assertFails(() -> service.claim(COUPON_ID, USER_ID), 40901, "券已抢光");

        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(2L);
        assertFails(() -> service.claim(COUPON_ID, USER_ID), 40901, "你已经领取过这张券");

        verify(couponMapper, never()).deductStock(any());
        verify(userCouponMapper, never()).insert(any());
    }

    @Test
    @DisplayName("Lua 返回 3=键没初始化：从数据库重建两个键，然后重试一次放行")
    void uninitializedKeysWarmUpAndRetry() {
        givenCoupon(coupon("50.00", "100.00", 7, 1));
        givenValueOps();
        givenSetOps();
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(3L, 0L);
        when(couponMapper.listClaimerIds(COUPON_ID)).thenReturn(List.of(11L, 22L));
        when(couponMapper.deductStock(COUPON_ID)).thenReturn(1);

        MyCouponVO vo = service.claim(COUPON_ID, USER_ID);

        assertThat(vo.name()).contains("满");
        verify(values).set(eq(STOCK_KEY), eq("7"), any(Duration.class));
        verify(setOps).add(CLAIMED_KEY, "11", "22");
        verify(redis).expire(eq(CLAIMED_KEY), any(Duration.class));
        // 预热只重试一次脚本：第一次 3、第二次 0
        verify(redis, times(2)).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    @DisplayName("预热出来的 TTL 至少留一段余量，不是「券一到期键就没了」")
    void warmedKeyKeepsABuffer() {
        Coupon soon = coupon("50.00", "100.00", 3, 1);
        soon.setEndTime(LocalDateTime.now().plusMinutes(10));
        when(couponMapper.getById(COUPON_ID)).thenReturn(soon);
        givenValueOps();
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(3L, 1L);
        when(couponMapper.listClaimerIds(COUPON_ID)).thenReturn(List.of());
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.captor();

        assertFails(() -> service.claim(COUPON_ID, USER_ID), 40901, "券已抢光");

        verify(values).set(eq(STOCK_KEY), eq("3"), ttl.capture());
        assertThat(ttl.getValue().getSeconds()).isGreaterThan(600);
    }

    @Test
    @DisplayName("领取成功写的是快照：名称、面额、门槛、到期时间都取自券模板，不是前端传的")
    void successWritesSnapshot() {
        Coupon template = coupon("50.00", "100.00", 10, 1);
        givenCoupon(template);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
        when(couponMapper.deductStock(COUPON_ID)).thenReturn(1);

        service.claim(COUPON_ID, USER_ID);

        ArgumentCaptor<UserCoupon> record = ArgumentCaptor.captor();
        verify(userCouponMapper).insert(record.capture());
        UserCoupon saved = record.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getCouponId()).isEqualTo(COUPON_ID);
        assertThat(saved.getShopId()).isEqualTo(template.getShopId());
        assertThat(saved.getName()).isEqualTo(template.getName());
        assertThat(saved.getAmount()).isEqualByComparingTo("50.00");
        assertThat(saved.getThresholdAmount()).isEqualByComparingTo("100.00");
        assertThat(saved.getStatus()).isEqualTo(UserCoupon.STATUS_UNUSED);
        assertThat(saved.getExpireTime()).isEqualTo(template.getEndTime());
        assertThat(saved.getReceiveTime()).isNotNull();
    }

    @Test
    @DisplayName("Redis 放行了但数据库乐观锁说没库存：把预扣还回去，两边不会越差越多")
    void dbStockRaceCompensatesRedis() {
        givenCoupon(coupon("50.00", "100.00", 10, 1));
        givenValueOps();
        givenSetOps();
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
        when(couponMapper.deductStock(COUPON_ID)).thenReturn(0);
        when(redis.hasKey(STOCK_KEY)).thenReturn(true);

        assertFails(() -> service.claim(COUPON_ID, USER_ID), 40901, "券已抢光");

        verify(setOps).remove(CLAIMED_KEY, String.valueOf(USER_ID));
        verify(values).increment(STOCK_KEY);
        verify(userCouponMapper, never()).insert(any());
    }

    /**
     * S-122 那条实测缺陷的守卫用例。
     *
     * 上一支测的是"数据库说没库存"（抛 ApiException，接得住）；这一支测的是
     * **语句超时**——`QueryTimeoutException` 属于 `DataAccessException` 系，
     * 修之前它直接从 claim() 穿出去，Redis 的预扣就永久留下了（实测少卖一张）。
     * 所以这条断言的价值不在"抛什么异常"，而在**异常原样传出去的同时，预扣必须归还**。
     */
    @Test
    @DisplayName("语句超时（DataAccessException 系）也必须归还预扣，否则就是少卖")
    void queryTimeoutAlsoCompensates() {
        givenCoupon(coupon("50.00", "100.00", 10, 1));
        givenValueOps();
        givenSetOps();
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
        when(couponMapper.deductStock(COUPON_ID))
                .thenThrow(new QueryTimeoutException("statement timed out after 10s"));
        when(redis.hasKey(STOCK_KEY)).thenReturn(true);

        assertThatThrownBy(() -> service.claim(COUPON_ID, USER_ID))
                .isInstanceOf(QueryTimeoutException.class);

        verify(setOps).remove(CLAIMED_KEY, String.valueOf(USER_ID));
        verify(values).increment(STOCK_KEY);
        verify(userCouponMapper, never()).insert(any());
    }

    /** 键已经不在了（比如刚被清）：归还时不许凭空 increment，那会造出一张不存在的库存。 */
    @Test
    @DisplayName("超时失败但库存键已消失：只摘名单，不无中生有加库存")
    void queryTimeoutWithoutStockKeyDoesNotInventStock() {
        givenCoupon(coupon("50.00", "100.00", 10, 1));
        givenSetOps();
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
        when(couponMapper.deductStock(COUPON_ID))
                .thenThrow(new QueryTimeoutException("statement timed out after 10s"));
        when(redis.hasKey(STOCK_KEY)).thenReturn(false);

        assertThatThrownBy(() -> service.claim(COUPON_ID, USER_ID))
                .isInstanceOf(QueryTimeoutException.class);

        verify(setOps).remove(CLAIMED_KEY, String.valueOf(USER_ID));
        verify(redis, never()).opsForValue();
    }

    @Test
    @DisplayName("唯一索引挡住一人多领：同样要补偿，并且回 40901 而不是 500")
    void duplicateKeyCompensatesAndConflicts() {
        givenCoupon(coupon("50.00", "100.00", 10, 1));
        givenValueOps();
        givenSetOps();
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
        when(couponMapper.deductStock(COUPON_ID)).thenReturn(1);
        when(userCouponMapper.insert(any())).thenThrow(new DuplicateKeyException("uk_coupon_user"));
        when(redis.hasKey(STOCK_KEY)).thenReturn(true);

        assertFails(() -> service.claim(COUPON_ID, USER_ID), 40901, "你已经领取过这张券");
        verify(values).increment(STOCK_KEY);
    }

    @Test
    @DisplayName("库存键已经不见了就不许 increment，否则会凭空造出一个「还有库存」来")
    void compensateSkipsIncrementWhenKeyGone() {
        givenCoupon(coupon("50.00", "100.00", 10, 1));
        givenSetOps();
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
        when(couponMapper.deductStock(COUPON_ID)).thenReturn(0);
        when(redis.hasKey(STOCK_KEY)).thenReturn(false);

        assertFails(() -> service.claim(COUPON_ID, USER_ID), 40901, "券已抢光");
        verify(values, never()).increment(anyString());
    }

    @Test
    @DisplayName("补偿本身失败不能把原始错误盖掉：仍然抛业务错，只是记一条 warn")
    void compensationFailureDoesNotMaskOriginalError() {
        givenCoupon(coupon("50.00", "100.00", 10, 1));
        givenSetOps();
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
        when(couponMapper.deductStock(COUPON_ID)).thenReturn(0);
        when(setOps.remove(CLAIMED_KEY, String.valueOf(USER_ID))).thenThrow(new IllegalStateException("redis down"));

        assertFails(() -> service.claim(COUPON_ID, USER_ID), 40901, "券已抢光");
    }

    @Test
    @DisplayName("Redis 没给出判定码就当作服务不可用，不能默认放行")
    void nullGateCodeIsServerError() {
        givenCoupon(coupon("50.00", "100.00", 10, 1));
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(null);

        assertFails(() -> service.claim(COUPON_ID, USER_ID), 50001, "暂时不可用");
        verify(couponMapper, never()).deductStock(any());
        // 没拿到判定码 = 这一笔扣没扣不知道，所以库存键必须弃掉，等下一次从数据库重建
        verify(redis).delete(STOCK_KEY);
    }

    /**
     * 预扣段超时：状态未知。此时**不能 increment** —— 万一脚本压根没执行，加回去就是凭空造库存，
     * 从少卖变成超卖。正确动作是删掉库存键让 warmUp 从数据库重建，并摘掉自己那条判重记录。
     * 这条用例是 WORKLOG S-122（少卖）之后的第二课：补偿的方向必须依据"我知道什么"。
     */
    @Test
    @DisplayName("预扣段超时：不猜方向，弃掉库存键而不是加回去")
    void gateTimeoutDiscardsStockInsteadOfIncrementing() {
        givenCoupon(coupon("50.00", "100.00", 10, 1));
        givenSetOps();
        when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new QueryTimeoutException("command timed out after 500ms"));

        assertThatThrownBy(() -> service.claim(COUPON_ID, USER_ID))
                .isInstanceOf(QueryTimeoutException.class);

        verify(redis).delete(STOCK_KEY);
        verify(setOps).remove(CLAIMED_KEY, String.valueOf(USER_ID));
        // 关键的反向断言：一个库存都不许凭空加出来
        verify(redis, never()).opsForValue();
        verify(couponMapper, never()).deductStock(any());
        verify(userCouponMapper, never()).insert(any());
    }

    /** 对照组：抢光是**确定结果**，缓存里的数是对的，谁都不许动它。 */
    @Test
    @DisplayName("闸门判定抢光：确定结果，不许碰缓存")
    void gateSoldOutLeavesCacheAlone() {
        givenCoupon(coupon("50.00", "100.00", 10, 1));
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);

        assertFails(() -> service.claim(COUPON_ID, USER_ID), 40901, "券已抢光");
        verify(redis, never()).delete(anyString());
        verify(redis, never()).opsForSet();
        verify(couponMapper, never()).deductStock(any());
    }

    @Test
    @DisplayName("我的券包三个词翻译成库里的数字，乱传 40001 而不是静默查全部")
    void myCouponStatusWords() {
        when(userCouponMapper.listByUser(USER_ID, UserCoupon.STATUS_USED)).thenReturn(List.of());
        service.my(USER_ID, "USED");
        verify(userCouponMapper).listByUser(USER_ID, UserCoupon.STATUS_USED);

        when(userCouponMapper.listByUser(eq(USER_ID), eq(null))).thenReturn(List.of());
        service.my(USER_ID, null);
        verify(userCouponMapper).listByUser(USER_ID, null);

        assertFails(() -> service.my(USER_ID, "BOGUS"), 40001, "status 只支持");
    }

    // ---- helpers ----

    /** 只 stub 真正会被走到的那半个 Redis：STRICT_STUBS 会把多出来的 stub 当失败报出来。 */
    private void givenValueOps() {
        when(redis.opsForValue()).thenReturn(values);
    }

    private void givenSetOps() {
        when(redis.opsForSet()).thenReturn(setOps);
    }

    private void givenCoupon(Coupon coupon) {
        when(couponMapper.getById(coupon.getId())).thenReturn(coupon);
    }

    private static Coupon coupon(String amount, String threshold, int stock, int status) {
        Coupon coupon = new Coupon();
        coupon.setId(COUPON_ID);
        coupon.setShopId(1L);
        coupon.setName("满" + threshold + "减" + amount);
        coupon.setAmount(new BigDecimal(amount));
        coupon.setThresholdAmount(new BigDecimal(threshold));
        coupon.setStock(stock);
        coupon.setStatus(status);
        coupon.setBeginTime(LocalDateTime.now().minusDays(1));
        coupon.setEndTime(LocalDateTime.now().plusDays(7));
        return coupon;
    }

    private static void assertFails(Runnable call, int code, String messagePart) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code))
                .hasMessageContaining(messagePart);
    }
}
