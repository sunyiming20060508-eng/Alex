package com.linli.mapper;

import com.linli.entity.UserCoupon;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

/** user_coupon 表数据访问。一人一券由唯一索引 uk_coupon_user 兜底。 */
@Mapper
public interface UserCouponMapper {

    /**
     * 写入领取记录。快照字段由调用方填好，这里不做默认值；
     * 唯一索引冲突会抛 DuplicateKeyException，由 Service 翻译成 40901「已经领取过」。
     */
    @Insert("insert into user_coupon (user_id, coupon_id, shop_id, name, amount, threshold_amount, "
            + "status, receive_time, expire_time) values (#{userId}, #{couponId}, #{shopId}, #{name}, "
            + "#{amount}, #{thresholdAmount}, #{status}, #{receiveTime}, #{expireTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserCoupon record);

    /** 我的优惠券。status 为空表示全部，按到期时间升序，快过期的排前面。 */
    List<UserCoupon> listByUser(@Param("userId") Long userId, @Param("status") Integer status);

    /** 下单时要按 id + userId 取券，别人的券查不到就是查不到，不靠上层记得判断归属。 */
    UserCoupon getByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 核销一张券：只有"未使用且属于这个人"才改得动。
     *
     * @return 0 表示这张券已被别的并发请求用掉了
     */
    int markUsed(@Param("id") Long id, @Param("userId") Long userId, @Param("orderId") Long orderId);

    /**
     * 订单取消、超时关闭或退款时把券还回去，否则用户的券会被一张根本没成交的订单吃掉。
     *
     * <p>和 {@code GoodsMapper.restoreStockByOrderIds} 一样带前置状态：SQL 自己 join orders 判
     * {@code o.status = expectStatus}。"先查出这批单 → 改状态 → 还券"之间用户可能刚好付款成功，
     * 那一单的券不能还——不加这个守卫就是资损：订单按折扣结了账，券又回到可用状态再花一次。
     *
     * @param expectStatus 只有处于这个状态的订单才允许释放它的券（已取消 / 已退款）
     * @return 实际还回去的张数
     */
    int releaseByOrderIds(@Param("orderIds") List<Long> orderIds, @Param("expectStatus") int expectStatus);
}
