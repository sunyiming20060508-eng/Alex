package com.linli.controller.user;

import com.linli.common.Result;
import com.linli.service.GoodsService;
import com.linli.vo.GoodsVO;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 商家团购套餐列表，匿名可看；下单动作在 POST /api/orders。 */
@RestController
@RequestMapping("/api/shops")
public class GoodsApiController {

    private final GoodsService goodsService;

    public GoodsApiController(GoodsService goodsService) { this.goodsService = goodsService; }

    @GetMapping("/{id}/goods")
    public Result<List<GoodsVO>> goods(@PathVariable("id") Long id) {
        return Result.ok(goodsService.listByShop(id));
    }
}
