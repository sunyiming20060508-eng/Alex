package com.linli.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linli.common.ApiException;
import com.linli.dto.AddressForm;
import com.linli.entity.UserAddress;
import com.linli.mapper.UserAddressMapper;
import com.linli.vo.AddressVO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 收货地址的单元测试。这里最值钱的一条是"越权和不存在回同一句话"：
 * 只要有一条路径先按 id 查再判归属，就会把"这条地址存在但不是你的"泄露出去。
 */
@ExtendWith(MockitoExtension.class)
class UserAddressServiceImplTest {

    private static final Long USER = 7L;
    private static final Long OTHER = 8L;
    private static final Long ID = 41L;

    @Mock private UserAddressMapper addressMapper;

    private UserAddressServiceImpl service;

    @BeforeEach
    void setUp() {
        // 不能在字段初始化里 new：那时 @Mock 还没注入，Service 拿到的是 null
        service = new UserAddressServiceImpl(addressMapper);
    }

    private static AddressForm form(String tag, Boolean isDefault) {
        return new AddressForm("张三", "13900001234", " 杭州市西湖区某路 1 号 ", tag, isDefault);
    }

    private static UserAddress entity(Long userId, int isDefault) {
        UserAddress address = new UserAddress();
        address.setId(ID);
        address.setUserId(userId);
        address.setConsignee("张三");
        address.setPhone("13900001234");
        address.setAddress("杭州市西湖区某路 1 号");
        address.setIsDefault(isDefault);
        address.setCreateTime(LocalDateTime.of(2026, 10, 26, 10, 0));
        return address;
    }

    @Test
    @DisplayName("第一条自动成为默认地址，不用多点一次")
    void firstAddressBecomesDefault() {
        when(addressMapper.countOfUserForUpdate(USER)).thenReturn(0L);
        when(addressMapper.getByIdAndUser(any(), any())).thenReturn(entity(USER, 1));

        AddressVO vo = service.add(USER, form("家", null));

        ArgumentCaptor<UserAddress> saved = ArgumentCaptor.forClass(UserAddress.class);
        verify(addressMapper).insert(saved.capture());
        assertThat(saved.getValue().getIsDefault()).isEqualTo(1);
        assertThat(vo.isDefault()).isTrue();
        // 地址文本两端空格不该存进去，否则列表里看着就是"两条一样的"
        assertThat(saved.getValue().getAddress()).isEqualTo("杭州市西湖区某路 1 号");
    }

    @Test
    @DisplayName("响应是回读出来的，不是拿内存对象拼的：create_time 不能是 null")
    void addReadsBackThePersistedRow() {
        when(addressMapper.countOfUserForUpdate(USER)).thenReturn(1L);
        when(addressMapper.getByIdAndUser(any(), any())).thenReturn(entity(USER, 0));

        AddressVO vo = service.add(USER, form(null, null));

        assertThat(vo.createTime()).isEqualTo("2026-10-26 10:00:00");
        assertThat(vo.tag()).isNull();
    }

    @Test
    @DisplayName("第 6 条被拒：上限是给\"选地址\"这个交互用的")
    void rejectsSixthAddress() {
        when(addressMapper.countOfUserForUpdate(USER)).thenReturn(5L);

        assertThatThrownBy(() -> service.add(USER, form("家", null)))
                .isInstanceOf(ApiException.class).hasMessage("最多保存 5 条收货地址");
        verify(addressMapper, never()).insert(any());
    }

    @Test
    @DisplayName("非默认地址新增时不去清别人的默认")
    void nonDefaultInsertKeepsExistingDefault() {
        when(addressMapper.countOfUserForUpdate(USER)).thenReturn(2L);
        when(addressMapper.getByIdAndUser(any(), any())).thenReturn(entity(USER, 0));

        service.add(USER, form("公司", false));

        verify(addressMapper, never()).clearDefault(any());
    }

    @Test
    @DisplayName("设默认是\"先清后置\"两条语句，顺序不能反")
    void setDefaultClearsThenMarks() {
        when(addressMapper.getByIdAndUser(ID, USER)).thenReturn(entity(USER, 0));

        service.setDefault(USER, ID);

        InOrder order = inOrder(addressMapper);
        order.verify(addressMapper).clearDefault(USER);
        order.verify(addressMapper).markDefault(ID, USER);
    }

    @Test
    @DisplayName("更新时传 isDefault=false 只是\"不动默认\"，不会把唯一的默认清掉")
    void updateWithFalseKeepsDefaultAsIs() {
        when(addressMapper.getByIdAndUser(ID, USER)).thenReturn(entity(USER, 1));
        when(addressMapper.update(any())).thenReturn(1);

        service.update(USER, ID, form("家", false));

        verify(addressMapper, never()).clearDefault(any());
        verify(addressMapper, never()).markDefault(any(), any());
    }

    @Test
    @DisplayName("改别人的地址、改不存在的地址：都是 404 同一句话")
    void updateHidesOwnership() {
        // Mapper 的 where 同时带 id 和 user_id，所以"不是你的"和"没有这条"取回来都是 null
        when(addressMapper.getByIdAndUser(ID, USER)).thenReturn(null);

        assertThatThrownBy(() -> service.update(USER, ID, form("家", null)))
                .isInstanceOf(ApiException.class).hasMessage("地址不存在");
        verify(addressMapper, never()).update(any());
    }

    @Test
    @DisplayName("删除同样带 user_id 守卫：别人的地址删不掉，也不报错说它存在")
    void deleteIsScopedToOwner() {
        when(addressMapper.getByIdAndUser(ID, OTHER)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(OTHER, ID))
                .isInstanceOf(ApiException.class).hasMessage("地址不存在");
        verify(addressMapper, never()).delete(any(), any());
    }

    @Test
    @DisplayName("列表顺序由 SQL 决定：默认在最前，Service 不再排一遍")
    void listKeepsSqlOrder() {
        when(addressMapper.listByUser(USER)).thenReturn(List.of(entity(USER, 1), entity(USER, 0)));

        List<AddressVO> list = service.list(USER);

        assertThat(list).extracting(AddressVO::isDefault).containsExactly(true, false);
    }
}
