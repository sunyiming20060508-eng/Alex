package com.linli.mapper;

import com.linli.dto.AdminReviewQueryDTO;
import com.linli.entity.Review;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * review 表数据访问。列表和回读都是"评价人信息一起带出来"的一条 join（定义在
 * resources/mapper/ReviewMapper.xml），避免为每条评价再查一次用户。
 *
 * 用户端的读语句（pageByShop / countByShop / averageScore）一律带 {@code status = 1}，
 * 管理端的（pageAdmin / countAdmin）不带——被驳回的评价对普通用户"不存在"，对管理员必须看得见。
 */
@Mapper
public interface ReviewMapper {

    @Insert("insert into review (user_id, shop_id, order_id, content, score, images) "
            + "values (#{userId}, #{shopId}, #{orderId}, #{content}, #{score}, #{images})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Review review);

    /** 刚插的那条按列表同一条 join 回读：响应里的作者是评价人，不是订单里别的人。 */
    Review getById(@Param("id") Long id);

    List<Review> pageByShop(@Param("shopId") Long shopId, @Param("offset") int offset, @Param("limit") int limit);

    long countByShop(@Param("shopId") Long shopId);

    /** 管理台分页与计数：同一套 where，定义在 XML，避免"总数和列表对不上"。 */
    List<Review> pageAdmin(AdminReviewQueryDTO query);

    long countAdmin(AdminReviewQueryDTO query);

    /** 我写的评价（带商家名）。用户端只看得到自己写的，所以这里**不按 status 过滤**：被驳回的那条作者有权知道。 */
    List<Review> pageByUser(@Param("userId") Long userId, @Param("offset") int offset, @Param("limit") int limit);

    long countByUser(@Param("userId") Long userId);

    /** 删自己的评价：id 和 user_id 一起进 where，删别人的天然是 0 行。 */
    @Delete("delete from review where id = #{id} and user_id = #{userId}")
    int deleteByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    /** 审核动作只改这一列；调用方负责重算展示分并失效商家详情缓存。 */
    @Update("update review set status = #{status} where id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    @Select("select count(*) from review where order_id = #{orderId}")
    int countByOrder(@Param("orderId") Long orderId);

    /** 订单列表用：一次问出这一页里哪些单已经评过，不在循环里逐单查。 */
    List<Long> listReviewedOrderIds(@Param("userId") Long userId, @Param("orderIds") List<Long> orderIds);

    /**
     * 商家当前平均分，没有评价时返回 null（调用方据此决定要不要动 shop.score）。
     * **只算已通过的评价**：驳回一条评价的效果必须是"它从来没影响过分数"，所以这里过滤 status。
     */
    @Select("select round(avg(score), 1) from review where shop_id = #{shopId} and status = 1")
    BigDecimal averageScore(@Param("shopId") Long shopId);
}
