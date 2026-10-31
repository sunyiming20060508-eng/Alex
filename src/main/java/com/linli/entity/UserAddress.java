package com.linli.entity;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * user_address 表实体。
 *
 * 地址是**下单时的快照来源**，不是订单的外键：orders 里已经有 consignee/phone/address 三列，
 * 下单那一刻把地址抄过去，之后用户改地址、删地址都不影响历史订单显示。所以这里可以硬删。
 */
@Data
public class UserAddress {

    private Long id;

    private Long userId;

    private String consignee;

    private String phone;

    private String address;

    /** 家 / 公司 / 学校，纯展示。 */
    private String tag;

    /** 1 是默认地址。每人至多一条，由 Service 在同一事务里"先清后置"保证。 */
    private Integer isDefault;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
