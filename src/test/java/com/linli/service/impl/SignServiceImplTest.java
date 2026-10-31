package com.linli.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linli.constant.RedisKeyConstant;
import com.linli.entity.PointsRecord;
import com.linli.entity.User;
import com.linli.mapper.SignRecordMapper;
import com.linli.mapper.UserMapper;
import com.linli.service.PointsService;
import com.linli.vo.SignVOs;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 签到的单元测试。钉住三件最容易写错的事：
 * ① 重复签到不能重复加分（唯一索引抛异常那条路）；② 满勤奖励只在刚好满 7 天那次发；
 * ③ Redis 键不在时要按数据库重建，而不是把用户已签的天数显示成 0。
 */
@ExtendWith(MockitoExtension.class)
class SignServiceImplTest {

    private static final Long USER = 7L;
    private static final LocalDate TODAY = LocalDate.now();
    private static final String KEY = RedisKeyConstant.signMonth(USER,
            TODAY.format(DateTimeFormatter.ofPattern("yyyyMM")));

    @Mock private SignRecordMapper signRecordMapper;
    @Mock private UserMapper userMapper;
    @Mock private PointsService pointsService;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;

    private SignServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SignServiceImpl(signRecordMapper, userMapper, pointsService, redis);
    }

    private void stubBitmap(Boolean hasKey) {
        when(redis.hasKey(KEY)).thenReturn(hasKey);
        when(redis.opsForValue()).thenReturn(values);
    }

    private void stubBalance(int points) {
        User user = new User();
        user.setId(USER);
        user.setPoints(points);
        when(userMapper.getById(USER)).thenReturn(user);
    }

    @Test
    @DisplayName("首次签到：落库 + 位图置位 + 加 1 分，本月天数按数据库算")
    void signsToday() {
        when(signRecordMapper.insert(USER, TODAY)).thenReturn(1);
        when(redis.opsForValue()).thenReturn(values);
        when(signRecordMapper.countBetween(USER, YearMonth.from(TODAY).atDay(1), YearMonth.from(TODAY).atEndOfMonth()))
                .thenReturn(1L);
        when(signRecordMapper.listRecentDates(eq(USER), anyInt())).thenReturn(List.of(TODAY));
        stubBalance(1);

        SignVOs.Result result = service.sign(USER);

        verify(values).setBit(eq(KEY), eq((long) TODAY.getDayOfMonth() - 1), eq(true));
        verify(redis).expire(eq(KEY), any(Duration.class));
        verify(pointsService).award(USER, 1, PointsRecord.TYPE_SIGN, null, "每日签到");
        assertThat(result.signed()).isTrue();
        assertThat(result.awarded()).isEqualTo(1);
        assertThat(result.monthDays()).isEqualTo(1);
        assertThat(result.continuousDays()).isEqualTo(1);
    }

    @Test
    @DisplayName("位图置位要等提交：后面 award 失败回滚了，位图不能留着谎称「今天已签」")
    void bitmapWriteWaitsForCommit() {
        when(signRecordMapper.insert(USER, TODAY)).thenReturn(1);
        when(redis.opsForValue()).thenReturn(values);
        when(signRecordMapper.countBetween(USER, YearMonth.from(TODAY).atDay(1), YearMonth.from(TODAY).atEndOfMonth()))
                .thenReturn(1L);
        when(signRecordMapper.listRecentDates(eq(USER), anyInt())).thenReturn(List.of(TODAY));
        stubBalance(1);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.sign(USER);

            verify(values, never()).setBit(anyString(), anyLong(), eq(true));

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(values).setBit(eq(KEY), eq((long) TODAY.getDayOfMonth() - 1), eq(true));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("重复签到（撞唯一索引）：不加分、不写流水，只回当前状态")
    void duplicateSignAwardsNothing() {
        when(signRecordMapper.insert(USER, TODAY)).thenThrow(new DuplicateKeyException("dup"));
        stubBitmap(true);
        when(values.getBit(KEY, TODAY.getDayOfMonth() - 1L)).thenReturn(true);
        when(values.bitField(anyString(), any())).thenReturn(List.of(6L));
        stubBalance(6);

        SignVOs.Result result = service.sign(USER);

        assertThat(result.signed()).isFalse();
        assertThat(result.awarded()).isZero();
        assertThat(result.message()).contains("已经签到");
        verify(pointsService, never()).award(any(), anyInt(), any(), any(), any());
        verify(values, never()).setBit(anyString(), anyLong(), eq(true));
    }

    @Test
    @DisplayName("满 7 天那次额外送 5 分；第 8 天再签不会重复领")
    void monthBonusFiresOnce() {
        when(signRecordMapper.insert(USER, TODAY)).thenReturn(1);
        when(redis.opsForValue()).thenReturn(values);
        when(signRecordMapper.countBetween(any(), any(), any())).thenReturn(7L);
        when(signRecordMapper.listRecentDates(eq(USER), anyInt())).thenReturn(List.of(TODAY));
        stubBalance(11);

        SignVOs.Result seventh = service.sign(USER);

        verify(pointsService).award(USER, 5, PointsRecord.TYPE_SIGN_MONTH, null, "本月签到满 7 天奖励");
        assertThat(seventh.awarded()).isEqualTo(6);
        assertThat(seventh.message()).contains("满 7 天");

        // 第 8 天：countBetween 变成 8，奖励那条分支不能再进
        org.mockito.Mockito.clearInvocations(pointsService);
        when(signRecordMapper.countBetween(any(), any(), any())).thenReturn(8L);
        service.sign(USER);
        verify(pointsService, times(1)).award(eq(USER), eq(1), eq(PointsRecord.TYPE_SIGN), any(), anyString());
        verify(pointsService, never()).award(eq(USER), eq(5), any(), any(), any());
    }

    @Test
    @DisplayName("位图键不在时按数据库重建再读：Redis 被清不会把已签天数显示成 0")
    void rebuildsMissingBitmapFromDatabase() {
        stubBitmap(false);
        List<LocalDate> days = List.of(TODAY, TODAY.minusDays(1), TODAY.minusDays(3));
        when(signRecordMapper.listRecentDates(eq(USER), anyInt())).thenReturn(days);
        when(values.bitField(anyString(), any())).thenReturn(List.of(3L));
        stubBalance(3);

        SignVOs.Status status = service.status(USER);

        // 本月内的两天要补回位图，跨月的那条（今天-3 天若落在上个月）不能补
        verify(values).setBit(KEY, TODAY.getDayOfMonth() - 1L, true);
        verify(values).setBit(KEY, TODAY.minusDays(1).getDayOfMonth() - 1L, true);
        verify(redis).expire(eq(KEY), any(Duration.class));
        assertThat(status.monthDays()).isEqualTo(2);
        assertThat(status.continuousDays()).isEqualTo(2);
    }

    @Test
    @DisplayName("今天没签时，连签天数从昨天往回数（不是直接算 0）")
    void continuousCountsFromYesterdayWhenTodayUnsigned() {
        stubBitmap(true);
        when(values.getBit(KEY, TODAY.getDayOfMonth() - 1L)).thenReturn(false);
        when(values.bitField(anyString(), any())).thenReturn(List.of(0L));
        when(signRecordMapper.listRecentDates(eq(USER), anyInt()))
                .thenReturn(List.of(TODAY.minusDays(1), TODAY.minusDays(2)));
        stubBalance(2);

        SignVOs.Status status = service.status(USER);

        assertThat(status.signedToday()).isFalse();
        assertThat(status.continuousDays()).isEqualTo(2);
    }

    @Test
    @DisplayName("一次都没签过：全 0，不抛")
    void neverSigned() {
        stubBitmap(true);
        when(values.getBit(KEY, TODAY.getDayOfMonth() - 1L)).thenReturn(false);
        when(values.bitField(anyString(), any())).thenReturn(List.of());
        when(signRecordMapper.listRecentDates(eq(USER), anyInt())).thenReturn(List.of());
        stubBalance(0);

        SignVOs.Status status = service.status(USER);

        assertThat(status.signedToday()).isFalse();
        assertThat(status.monthDays()).isZero();
        assertThat(status.continuousDays()).isZero();
    }
}
