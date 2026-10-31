package com.linli.service.impl;

import com.linli.common.ApiException;
import com.linli.common.ErrorCode;
import com.linli.common.PageResult;
import com.linli.entity.PointsRecord;
import com.linli.entity.User;
import com.linli.mapper.PointsRecordMapper;
import com.linli.mapper.UserMapper;
import com.linli.service.PointsService;
import com.linli.vo.SignVOs;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.linli.common.Pagination;

/**
 * 积分的实现。写顺序是"先改余额、再落流水"，两步同一个事务
 * （{@link #award} 自己标 {@code @Transactional}，不靠调用方带进来——
 * 签到那边确实有事务，但**给不出账的调用方将来会一直增加**，
 * 把原子性写在自己的方法上才不用每次都重新检查一遍）：
 * 余额那条 UPDATE 带 {@code points + delta >= 0} 守卫，影响 0 行就说明这分数给不出/扣不动，
 * 直接抛错回滚，不会留下一条对不上账的流水。
 */
@Service
public class PointsServiceImpl implements PointsService {

    private static final Logger log = LoggerFactory.getLogger(PointsServiceImpl.class);

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;

    private final UserMapper userMapper;
    private final PointsRecordMapper pointsRecordMapper;

    public PointsServiceImpl(UserMapper userMapper, PointsRecordMapper pointsRecordMapper) {
        this.userMapper = userMapper;
        this.pointsRecordMapper = pointsRecordMapper;
    }

    @Override
    @Transactional
    public int award(Long userId, int delta, String type, Long refId, String content) {
        if (userId == null || delta == 0) return 0;
        int changed = userMapper.updatePoints(userId, delta);
        if (changed == 0) {
            throw new ApiException(delta < 0 ? ErrorCode.PARAM_INVALID : ErrorCode.NOT_FOUND,
                    delta < 0 ? "积分余额不足" : "用户不存在");
        }
        User user = userMapper.getById(userId);
        int balance = user == null || user.getPoints() == null ? 0 : user.getPoints();
        PointsRecord record = new PointsRecord();
        record.setUserId(userId);
        record.setDelta(delta);
        record.setBalance(balance);
        record.setType(type);
        record.setRefId(refId);
        record.setContent(content);
        pointsRecordMapper.insert(record);
        log.debug("积分已变更: userId={} delta={} 余额={} type={} refId={}", userId, delta, balance, type, refId);
        return balance;
    }

    @Override
    public PageResult<SignVOs.PointsRow> pageMine(Long userId, int page, int pageSize) {
        Pagination query = Pagination.of(page, pageSize, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        int safePage = query.page();
        int safeSize = query.size();
        long total = pointsRecordMapper.countByUser(userId);
        List<SignVOs.PointsRow> list = total == 0 ? List.of()
                : pointsRecordMapper.pageByUser(userId, (safePage - 1) * safeSize, safeSize).stream()
                        .map(SignVOs.PointsRow::from).toList();
        return new PageResult<>(list, total, safePage, safeSize);
    }
}
