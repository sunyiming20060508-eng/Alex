package com.linli.controller.user;

import com.linli.annotation.RequiresLogin;
import com.linli.common.Result;
import com.linli.context.UserContext;
import com.linli.dto.AddressForm;
import com.linli.service.UserAddressService;
import com.linli.vo.AddressVO;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 收货地址的增删改查，挂在 {@code /api/user/me/addresses} 下——"我的"这类资源一律带 me，
 * 用户 id 只从会话取，请求里永远不出现 userId。
 *
 * 地址只负责**下单时一键回填**：{@code POST /api/orders} 仍然自己收 consignee/phone/address 并快照进订单，
 * 不认 addressId。这样"改了地址历史订单跟着变"这种事故不可能发生，服务端定价那条链也不用改动。
 */
@RestController
@RequestMapping("/api/user/me/addresses")
@RequiresLogin
public class AddressApiController {

    private final UserAddressService addressService;

    public AddressApiController(UserAddressService addressService) {
        this.addressService = addressService;
    }

    /** 默认地址在最前，其余按新增倒序；数量就是上限 5，所以不分页。 */
    @GetMapping
    public Result<List<AddressVO>> list() {
        return Result.ok(addressService.list(UserContext.requireUserId()));
    }

    @PostMapping
    public Result<AddressVO> add(@Valid @RequestBody AddressForm form) {
        return Result.ok(addressService.add(UserContext.requireUserId(), form));
    }

    @PutMapping("/{id}")
    public Result<AddressVO> update(@PathVariable Long id, @Valid @RequestBody AddressForm form) {
        return Result.ok(addressService.update(UserContext.requireUserId(), id, form));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        addressService.delete(UserContext.requireUserId(), id);
        return Result.ok(null);
    }

    /** 换默认地址：先清后置，两条语句同一个事务。 */
    @PostMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        addressService.setDefault(UserContext.requireUserId(), id);
        return Result.ok(null);
    }
}
