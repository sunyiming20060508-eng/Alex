package com.linli.mapper;

import com.linli.entity.OrderDetail;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** order_detail 表数据访问。 */
@Mapper
public interface OrderDetailMapper {

    /** 批量插入。用 foreach 一次写入，不要在外层循环单条 insert。 */
    int insertBatch(@Param("list") List<OrderDetail> details);

    /**
     * 一次取回多个订单的明细，配合 Service 里的 groupingBy 回填，消灭"每单再查一次明细"的 N+1。
     */
    List<OrderDetail> listByOrderIds(@Param("orderIds") List<Long> orderIds);
}
