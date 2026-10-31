package com.linli.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linli.common.ApiException;
import com.linli.common.PageResult;
import com.linli.entity.PointsRecord;
import com.linli.entity.User;
import com.linli.mapper.PointsRecordMapper;
import com.linli.mapper.UserMapper;
import com.linli.vo.SignVOs;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

/**
 * 积分的单元测试。核心是那条**余额和流水必须一起成立**：
 * 守卫没让过（影响 0 行）时绝不能留下一条流水，否则翻账就会看到"有流水没余额变化"。
 */
@ExtendWith(MockitoExtension.class)
class PointsServiceImplTest {

    private static final Long USER = 7L;

    @Mock private UserMapper userMapper;
    @Mock private PointsRecordMapper pointsRecordMapper;

    private PointsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PointsServiceImpl(userMapper, pointsRecordMapper);
    }

    private static User user(int points) {
        User user = new User();
        user.setId(USER);
        user.setPoints(points);
        return user;
    }

    @Test
    @DisplayName("加分：改余额 → 回读真实余额 → 流水带余额快照")
    void awardWritesFlowWithBalanceSnapshot() {
        when(userMapper.updatePoints(USER, 5)).thenReturn(1);
        when(userMapper.getById(USER)).thenReturn(user(12));

        int balance = service.award(USER, 5, PointsRecord.TYPE_SIGN, null, "每日签到");

        assertThat(balance).isEqualTo(12);
        ArgumentCaptor<PointsRecord> captor = ArgumentCaptor.forClass(PointsRecord.class);
        verify(pointsRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getBalance()).isEqualTo(12);
        assertThat(captor.getValue().getDelta()).isEqualTo(5);
        assertThat(captor.getValue().getType()).isEqualTo("SIGN");
    }

    @Test
    @DisplayName("扣分会把余额扣成负数时：守卫返回 0 → 抛错，且一条流水都不写")
    void refusesNegativeBalance() {
        when(userMapper.updatePoints(USER, -20)).thenReturn(0);

        assertThatThrownBy(() -> service.award(USER, -20, PointsRecord.TYPE_ORDER, 1L, "扣分"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("积分余额不足");

        verify(pointsRecordMapper, never()).insert(any());
        verify(userMapper, never()).getById(any());
    }

    @Test
    @DisplayName("加分为 0 或没有用户 id：什么都不做（不给账本留一条空流水）")
    void ignoresNoop() {
        assertThat(service.award(null, 5, PointsRecord.TYPE_SIGN, null, "x")).isZero();
        assertThat(service.award(USER, 0, PointsRecord.TYPE_SIGN, null, "x")).isZero();

        verify(userMapper, never()).updatePoints(any(), anyInt());
        verify(pointsRecordMapper, never()).insert(any());
    }

    @Test
    @DisplayName("用户不存在时加分回 404，而不是写一条没人认领的流水")
    void unknownUserGetsNotFound() {
        when(userMapper.updatePoints(USER, 1)).thenReturn(0);

        assertThatThrownBy(() -> service.award(USER, 1, PointsRecord.TYPE_SIGN, null, "每日签到"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("用户不存在");

        verify(pointsRecordMapper, never()).insert(any());
    }

    @Test
    @DisplayName("流水分页：总数为 0 就不查列表；有行时把时间格式化成契约口径")
    void pageMine() {
        when(pointsRecordMapper.countByUser(USER)).thenReturn(0L);
        PageResult<SignVOs.PointsRow> empty = service.pageMine(USER, 1, 10);
        assertThat(empty.list()).isEmpty();
        verify(pointsRecordMapper, never()).pageByUser(any(), anyInt(), anyInt());

        PointsRecord record = new PointsRecord();
        record.setId(3L);
        record.setUserId(USER);
        record.setDelta(5);
        record.setBalance(12);
        record.setType("SIGN");
        record.setContent("每日签到");
        record.setCreateTime(LocalDateTime.of(2026, 10, 26, 21, 0));
        when(pointsRecordMapper.countByUser(USER)).thenReturn(1L);
        when(pointsRecordMapper.pageByUser(USER, 0, 50)).thenReturn(List.of(record));

        PageResult<SignVOs.PointsRow> page = service.pageMine(USER, 0, 999);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(50);
        assertThat(page.list().get(0).createdAt()).isEqualTo("2026-10-26 21:00:00");
        assertThat(page.list().get(0).balance()).isEqualTo(12);
    }

    /**
     * 这条测的不是行为，是**契约**：类注释承诺"改余额和落流水同一个事务"，
     * 而它此前只在唯一调用方（签到）自己带事务时才成立——裸调一次就会留下"改了余额没落流水"
     * 这种对不上账的半截状态，而 Mockito 的单测根本看不见事务，摘掉注解也照样全绿。
     * 所以用反射钉住：以后谁把它删了，这条立刻红。
     */
    @Test
    @DisplayName("award 自带 @Transactional：原子性写在自己方法上，不靠调用方带进来")
    void awardDeclaresItsOwnTransaction() throws NoSuchMethodException {
        Method award = PointsServiceImpl.class.getMethod(
                "award", Long.class, int.class, String.class, Long.class, String.class);

        assertThat(award.isAnnotationPresent(Transactional.class)).isTrue();
    }
}
