package com.linli.controller.user;

import com.linli.annotation.RequiresLogin;
import com.linli.common.PageResult;
import com.linli.common.Result;
import com.linli.context.UserContext;
import com.linli.dto.OrderSubmitDTO;
import com.linli.service.OrderService;
import com.linli.vo.OrderVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 目标订单接口。整个类都要求登录（类上的 {@link RequiresLogin}），所以方法里一律从
 * {@code UserContext} 取当前用户，userId 不出现在请求参数里——想查别人的单，SQL 层就查不到。
 *
 * 支付是 mock：没有接任何支付渠道，pay 就是把状态从"待支付"推到"待使用"。
 * complete 是商家核销的代跑接口，管理端上线后挪到 /api/admin 下面。
 */
@RestController
@RequestMapping("/api/orders")
@RequiresLogin
public class OrderApiController {

    private final OrderService orderService;

    public OrderApiController(OrderService orderService) { this.orderService = orderService; }

    @PostMapping
    public Result<OrderVO> submit(@Valid @RequestBody OrderSubmitDTO form) {
        return Result.ok(orderService.submit(form, UserContext.requireUserId()));
    }

    @GetMapping
    public Result<PageResult<OrderVO>> page(@RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int pageSize,
                                            @RequestParam(required = false) String status) {
        return Result.ok(orderService.page(page, pageSize, status, UserContext.requireUserId()));
    }

    @GetMapping("/{id}")
    public Result<OrderVO> detail(@PathVariable("id") Long id) {
        return Result.ok(orderService.detail(id, UserContext.requireUserId()));
    }

    @PostMapping("/{id}/pay")
    public Result<OrderVO> pay(@PathVariable("id") Long id) {
        return Result.ok(orderService.pay(id, UserContext.requireUserId()));
    }

    @PostMapping("/{id}/cancel")
    public Result<OrderVO> cancel(@PathVariable("id") Long id) {
        return Result.ok(orderService.cancel(id, UserContext.requireUserId()));
    }

    /** 申请退款：只有"待使用"能退，退还库存和券；重复调用回 40901，不会退两次。 */
    @PostMapping("/{id}/refund")
    public Result<OrderVO> refund(@PathVariable Long id) {
        return Result.ok(orderService.refund(id, UserContext.requireUserId()));
    }

    @PostMapping("/{id}/complete")
    public Result<OrderVO> complete(@PathVariable("id") Long id) {
        return Result.ok(orderService.complete(id, UserContext.requireUserId()));
    }
}
