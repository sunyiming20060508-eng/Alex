package com.linli.service;

import com.linli.common.PageResult;
import com.linli.dto.CouponQueryDTO;
import com.linli.vo.CouponVO;
import com.linli.vo.MyCouponVO;
import java.util.List;

/** 优惠券：可领取列表、商家维度列表、我的券包，以及领取。 */
public interface CouponService {

    PageResult<CouponVO> page(CouponQueryDTO query);

    /** 商家详情页的可用券。userId 可为空（未登录），只影响 received 字段。 */
    List<CouponVO> listByShop(Long shopId, Long userId);

    /**
     * 我的优惠券。status 用契约里的三个词 AVAILABLE/USED/EXPIRED（大小写不敏感），
     * 传 null 或空串表示全部；其它取值抛 40001。数据库里的 1/2/3 由实现层翻译，不外泄到接口签名。
     */
    List<MyCouponVO> my(Long userId, String status);

    /**
     * 领取一张券（对外是 POST /api/coupons/{id}/claim）。
     * 成功返回领取记录；库存不足、重复领取、券已下架分别抛 40901/40401。
     * 重复调用同一张券不会多扣库存，由唯一索引保证幂等。调用方必须已登录，userId 来自 UserContext。
     */
    MyCouponVO claim(Long couponId, Long userId);
}
