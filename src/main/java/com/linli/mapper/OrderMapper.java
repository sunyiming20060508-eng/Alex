package com.linli.mapper;

import com.linli.dto.OrderPageQueryDTO;
import com.linli.entity.Order;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * orders 表数据访问。状态变更全部是**守卫式 UPDATE**：把"该不该改"写进 where 条件，
 * 返回 0 就代表不该改（状态不对、不是你的单），校验和写入合成一步，天然防并发重复操作。
 */
@Mapper
public interface OrderMapper {

    /** 插入主单。useGeneratedKeys 回填 id，明细紧接着要用，省一次回查。 */
    int insert(Order order);

    /** 按 id + userId 取单，越权访问在 SQL 层就是查不到行。 */
    Order getByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    List<Order> pageQuery(OrderPageQueryDTO query);

    long countQuery(OrderPageQueryDTO query);

    /**
     * 带前置状态守卫的更新。patch 里只放要改的字段，expectStatusList 是允许从哪些状态过来。
     *
     * @return 影响行数，0 表示状态不符或不是这个用户的单
     */
    int updateStatusWithGuard(@Param("order") Order patch,
                              @Param("userId") Long userId,
                              @Param("expectStatusList") List<Integer> expectStatusList);

    /**
     * 超时未支付的订单 id。分成"先查 id 再改"而不是一条 UPDATE 到底，是因为这些订单可能占着
     * 一张已核销的券，必须拿到 id 才能把券还回去。
     */
    List<Long> listTimeoutIds(@Param("deadline") LocalDateTime deadline,
                              @Param("pendingStatus") int pendingStatus);

    /**
     * 按 id 批量取消，条件里仍带前置状态，避免把这一瞬间刚被用户支付的单也关掉。
     *
     * @return 被取消的订单数
     */
    int cancelByIds(@Param("ids") List<Long> ids,
                    @Param("reason") String reason,
                    @Param("pendingStatus") int pendingStatus,
                    @Param("cancelledStatus") int cancelledStatus);
}
