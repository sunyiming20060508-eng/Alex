package com.linli.service;

import com.linli.vo.SignVOs;

/** 每日签到。真相在 sign_record（一天一条，唯一索引挡重复），Redis Bitmap 只负责读得快。 */
public interface SignService {

    /** 签到。今天已经签过不会报错，也不会重复加分——回的是"当前状态"，不是"这次动作成功与否"。 */
    SignVOs.Result sign(Long userId);

    /** 签到页打开时的状态：今天签没签、本月几天、连签几天、当前积分。 */
    SignVOs.Status status(Long userId);
}
