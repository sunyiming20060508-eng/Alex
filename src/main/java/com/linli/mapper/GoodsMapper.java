package com.linli.mapper;

import com.linli.entity.Goods;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** goods 表数据访问。库存扣减和回补都在这里，价格一律由服务端读出，不接受前端传值。 */
@Mapper
public interface GoodsMapper {

    @Select("select g.*, s.name as shop_name from goods g "
            + "left join shop s on s.id = g.shop_id "
            + "where g.shop_id = #{shopId} and g.status = 1 order by g.sort asc, g.id asc")
    List<Goods> listOnSaleByShop(@Param("shopId") Long shopId);

    @Select("select g.*, s.name as shop_name from goods g "
            + "left join shop s on s.id = g.shop_id where g.id = #{id}")
    Goods getById(@Param("id") Long id);

    /**
     * 下单用的批量读：N 个套餐一次 IN 查询读回来，替代循环里逐个 {@link #getById}
     * （那一版三个套餐就是三次往返，见 docs/WORKLOG.md S-135）。
     * 列和 {@code getById} 完全一致，因为 Service 要算价、判下架、判归属，不能换一副形状。
     * <b>返回顺序不保证</b>，调用方按 id 建 Map、再按下单明细的顺序取；查不到的 id 就是不存在。
     */
    List<Goods> listByIds(@Param("ids") List<Long> ids);

    /** 下单扣库存。条件带 {@code stock >= quantity}，返回 0 表示没抢到，不会扣成负数。 */
    @Update("update goods set stock = stock - #{quantity} where id = #{id} and stock >= #{quantity}")
    int deductStock(@Param("id") Long id, @Param("quantity") int quantity);

    /**
     * 订单取消或超时关闭时回补库存。条件里带上"这一单确实是已取消状态"，
     * 避免在"查待超时 id"和"回补"之间用户刚好付款的竞态里把库存多加一份。
     */
    int restoreStockByOrderIds(@Param("orderIds") List<Long> orderIds,
                               @Param("cancelledStatus") int cancelledStatus);
}
