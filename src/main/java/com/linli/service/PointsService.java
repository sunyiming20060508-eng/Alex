package com.linli.service;

import com.linli.common.PageResult;
import com.linli.vo.SignVOs;

/**
 * 积分：余额 + 流水。
 *
 * 只有这一个出口能改 {@code user.points}。别的业务（签到、消费返分）要加分就调 {@link #award}，
 * 不要自己去 update user 表——那样流水和余额迟早对不上。
 */
public interface PointsService {

    /**
     * 加/扣积分并写一条流水。**必须在调用方的事务里**：余额变了而流水没落下，等于账目不平。
     *
     * @param delta 正数加分、负数扣分
     * @return 变化后的余额
     */
    int award(Long userId, int delta, String type, Long refId, String content);

    /** 我的积分流水，按时间倒序分页。 */
    PageResult<SignVOs.PointsRow> pageMine(Long userId, int page, int pageSize);
}
