package com.linli.mapper;

import com.linli.dto.CouponQueryDTO;
import com.linli.entity.Coupon;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** coupon 表数据访问。分页和"是否已领过"要拼动态条件，写在 resources/mapper/CouponMapper.xml。 */
@Mapper
public interface CouponMapper {

    /** 领取流程要读权威库存，所以这里读数据库而不是读缓存。 */
    @Select("select c.*, s.name as shop_name from coupon c "
            + "left join shop s on s.id = c.shop_id where c.id = #{id}")
    Coupon getById(Long id);

    /**
     * 扣减剩余库存。条件里带 {@code stock > 0}，返回 0 表示没抢到——
     * 这是"用 gt 而不是用等于快照值"的那条结论：等于快照会少卖，大于 0 才既不超卖也不少卖。
     */
    @Update("update coupon set stock = stock - 1 where id = #{id} and stock > 0")
    int deductStock(@Param("id") Long id);

    /** 预热 Redis 已领集合用：这张券当前所有领取人的 id。 */
    @Select("select user_id from user_coupon where coupon_id = #{couponId}")
    List<Long> listClaimerIds(@Param("couponId") Long couponId);

    List<Coupon> pageQuery(CouponQueryDTO query);

    long countQuery(CouponQueryDTO query);

    /** 商家详情页的可用券，按到期时间升序，最多 20 张。 */
    List<Coupon> listByShop(@Param("shopId") Long shopId, @Param("userId") Long userId);
}
