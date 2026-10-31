package com.linli.service.impl;

import com.linli.common.AfterCommit;
import com.linli.constant.RedisKeyConstant;
import com.linli.entity.PointsRecord;
import com.linli.entity.User;
import com.linli.mapper.SignRecordMapper;
import com.linli.mapper.UserMapper;
import com.linli.service.PointsService;
import com.linli.service.SignService;
import com.linli.vo.SignVOs;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 签到的实现。三件事各自有各自的存储，别混：
 *
 * 1. **今天能不能再签由数据库唯一索引决定**（{@code uk_sign_user_date}）。这是业务约束，
 *    交给 Redis 就等于承认「键丢了就能重复领分」。
 * 2. **今天签没签、本月签了几天读 Redis Bitmap**：offset 是这个月的第几天（0 起），
 *    一次 GETBIT 加一次 BITFIELD 就出结果。键不在（过期或被清）时按 sign_record 重建再读，
 *    所以 Redis 挂了最多慢一点，不会把用户已经签过的天数显示成 0。
 * 3. **连签几天用数据库最近几条日期在 Java 里往回连**。Bitmap 也能算，但那是为了省一次索引查询
 *    把简单的事写复杂——一个月最多 31 行，走唯一索引倒序取就行。
 */
@Service
public class SignServiceImpl implements SignService {

    private static final Logger log = LoggerFactory.getLogger(SignServiceImpl.class);

    /** 每日签到 1 分。 */
    private static final int POINTS_PER_SIGN = 1;

    /** 本月签满 7 天再送 5 分；只在「刚好满 7 天」那一次发，所以不可能重复领。 */
    private static final int MONTH_FULL_DAYS = 7;
    private static final int MONTH_FULL_BONUS = 5;

    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyyMM");

    /** 往回连最多看这么多条，够覆盖跨月连签。 */
    private static final int RECENT_DATES = 62;

    /** 一个月最多 31 天，取 32 位读回来正好覆盖这个月的位段。 */
    private static final int SIGN_BITS = 32;

    private final SignRecordMapper signRecordMapper;
    private final UserMapper userMapper;
    private final PointsService pointsService;
    private final StringRedisTemplate redis;

    public SignServiceImpl(SignRecordMapper signRecordMapper, UserMapper userMapper,
                           PointsService pointsService, StringRedisTemplate redis) {
        this.signRecordMapper = signRecordMapper;
        this.userMapper = userMapper;
        this.pointsService = pointsService;
        this.redis = redis;
    }

    @Override
    @Transactional
    public SignVOs.Result sign(Long userId) {
        LocalDate today = LocalDate.now();
        try {
            signRecordMapper.insert(userId, today);
        } catch (DuplicateKeyException e) {
            // 连点两次、或者今天已经签过：不重复加分，但状态照样回，前端要显示「已签到」
            log.debug("重复签到，忽略: userId={} date={}", userId, today);
            SignVOs.Status current = status(userId);
            return new SignVOs.Result(false, 0, current.points(), current.monthDays(),
                    current.continuousDays(), "今天已经签到过了");
        }
        markSigned(userId, today);
        pointsService.award(userId, POINTS_PER_SIGN, PointsRecord.TYPE_SIGN, null, "每日签到");

        YearMonth month = YearMonth.from(today);
        int monthDays = (int) signRecordMapper.countBetween(userId, month.atDay(1), month.atEndOfMonth());
        int awarded = POINTS_PER_SIGN;
        String message = "签到成功，+" + POINTS_PER_SIGN + " 积分";
        if (monthDays == MONTH_FULL_DAYS) {
            awarded += MONTH_FULL_BONUS;
            pointsService.award(userId, MONTH_FULL_BONUS, PointsRecord.TYPE_SIGN_MONTH, null,
                    "本月签到满 " + MONTH_FULL_DAYS + " 天奖励");
            message = message + "，满 " + MONTH_FULL_DAYS + " 天再送 " + MONTH_FULL_BONUS + " 分";
        }
        log.debug("签到成功: userId={} date={} 本月第 {} 天 加分={}", userId, today, monthDays, awarded);
        return new SignVOs.Result(true, awarded, balance(userId), monthDays,
                continuousDays(userId, today), message);
    }

    @Override
    public SignVOs.Status status(Long userId) {
        LocalDate today = LocalDate.now();
        String key = signedKey(userId, today);
        if (!Boolean.TRUE.equals(redis.hasKey(key))) {
            rebuild(userId, YearMonth.from(today), key);
        }
        boolean signedToday = Boolean.TRUE.equals(redis.opsForValue().getBit(key, today.getDayOfMonth() - 1L));
        return new SignVOs.Status(signedToday, signedDays(key), continuousDays(userId, today),
                balance(userId), today);
    }

    /**
     * 本月签了几天：一次 BITFIELD 把这个月的位段读成一个整数，再在 Java 里数 1 的个数。
     * 不用 BITCOUNT 是因为它的 start/end 是**字节**下标，对不齐「某个人某个月的 31 位」；
     * 而这个键本来就只装一个用户一个月，取 u32 就是全键。
     */
    private int signedDays(String key) {
        List<Long> values = redis.opsForValue().bitField(key, BitFieldSubCommands.create()
                .get(BitFieldSubCommands.BitFieldType.unsigned(SIGN_BITS)).valueAt(0));
        if (values == null || values.isEmpty() || values.get(0) == null) return 0;
        return Long.bitCount(values.get(0));
    }

    private void markSigned(Long userId, LocalDate today) {
        String key = signedKey(userId, today);
        // 位图是"读加速层"，写它属于外部副作用：放在提交后，否则后面 award 失败回滚了，
        // 数据库里没这条签到记录、位图却已经置位，status() 会一直显示"今天已签"到月末。
        AfterCommit.run(() -> {
            redis.opsForValue().setBit(key, today.getDayOfMonth() - 1L, true);
            redis.expire(key, Duration.ofSeconds(RedisKeyConstant.SIGN_TTL_SECONDS));
        });
    }

    /** 位图键不在时按数据库重建——「Redis 只当加速层」就是这么落地的。 */
    private void rebuild(Long userId, YearMonth month, String key) {
        for (LocalDate date : signRecordMapper.listRecentDates(userId, RECENT_DATES)) {
            if (YearMonth.from(date).equals(month)) {
                redis.opsForValue().setBit(key, date.getDayOfMonth() - 1L, true);
            }
        }
        redis.expire(key, Duration.ofSeconds(RedisKeyConstant.SIGN_TTL_SECONDS));
    }

    /** 从今天（今天没签则从昨天）往回连着数。 */
    private int continuousDays(Long userId, LocalDate today) {
        List<LocalDate> dates = signRecordMapper.listRecentDates(userId, RECENT_DATES);
        if (dates.isEmpty()) return 0;
        Set<LocalDate> signed = new HashSet<>(dates);
        LocalDate cursor = signed.contains(today) ? today : today.minusDays(1);
        int days = 0;
        while (signed.contains(cursor)) {
            days++;
            cursor = cursor.minusDays(1);
        }
        return days;
    }

    private int balance(Long userId) {
        User user = userId == null ? null : userMapper.getById(userId);
        return user == null || user.getPoints() == null ? 0 : user.getPoints();
    }

    private static String signedKey(Long userId, LocalDate date) {
        return RedisKeyConstant.signMonth(userId, date.format(MONTH_KEY));
    }
}
