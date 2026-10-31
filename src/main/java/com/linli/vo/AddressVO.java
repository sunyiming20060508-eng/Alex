package com.linli.vo;

import com.linli.common.TimeText;
import com.linli.entity.UserAddress;

/** 收货地址返回对象。{@code isDefault} 出接口时已经是布尔值，前端不用再去比 1/0。 */
public record AddressVO(Long id, String consignee, String phone, String address, String tag,
                        boolean isDefault, String createTime) {

    public static AddressVO from(UserAddress entity) {
        if (entity == null) return null;
        return new AddressVO(entity.getId(), entity.getConsignee(), entity.getPhone(), entity.getAddress(),
                entity.getTag(), entity.getIsDefault() != null && entity.getIsDefault() == 1,
                TimeText.format(entity.getCreateTime()));
    }
}
