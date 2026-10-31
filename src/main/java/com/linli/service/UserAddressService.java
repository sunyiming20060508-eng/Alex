package com.linli.service;

import com.linli.dto.AddressForm;
import com.linli.vo.AddressVO;
import java.util.List;

/**
 * 收货地址。所有方法的 {@code userId} 都来自会话，方法内每一条 SQL 都带 {@code user_id} 守卫，
 * 所以"越权改别人的地址"和"地址不存在"是同一种结果：404，不泄露"这条存在但不是你的"。
 */
public interface UserAddressService {

    List<AddressVO> list(Long userId);

    AddressVO add(Long userId, AddressForm form);

    AddressVO update(Long userId, Long id, AddressForm form);

    void delete(Long userId, Long id);

    /** 把某条设成默认：先清掉原来的再置新的，两条语句在同一个事务里。 */
    void setDefault(Long userId, Long id);
}
