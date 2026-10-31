package com.linli.controller.user;

import com.linli.common.PageResult;
import com.linli.common.Result;
import com.linli.dto.ShopPageQueryDTO;
import com.linli.service.CategoryService;
import com.linli.service.ShopService;
import com.linli.vo.CategoryVO;
import com.linli.vo.OptionVO;
import com.linli.vo.ShopVO;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 目标商家接口（API_CONTRACT.md 第 6 节）。数据来自 shop/category 两张表，
 * 本类只负责参数装配、VO 封装和统一响应，不写 SQL 也不做缓存判断。
 * 旧的 /shop/list、/shop/{id} 保留在 ShopController，互不影响。
 */
@RestController
@RequestMapping("/api")
public class ShopApiController {

    private final ShopService shopService;
    private final CategoryService categoryService;

    public ShopApiController(ShopService shopService, CategoryService categoryService) {
        this.shopService = shopService;
        this.categoryService = categoryService;
    }

    @GetMapping("/categories")
    public Result<List<CategoryVO>> categories() {
        return Result.ok(categoryService.list());
    }

    /**
     * 商家分页列表。categoryId 对应 category 表主键；keyword 同时匹配商家名和分类名。
     * sort 支持 distance（最近）/ score（最高）/ price（人均最低），白名单和归一化都在 Service 里做，
     * 非法值回 40001 而不是悄悄按默认排。
     */
    @GetMapping("/shops")
    public Result<PageResult<ShopVO>> shops(@RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int pageSize,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) Long categoryId,
                                            @RequestParam(required = false) String sort,
                                            @RequestParam(required = false) String city) {
        ShopPageQueryDTO query = new ShopPageQueryDTO();
        query.setPage(page);
        query.setPageSize(pageSize);
        query.setKeyword(keyword);
        query.setCategoryId(categoryId);
        query.setSort(sort);
        query.setCity(city);
        return Result.ok(shopService.page(query));
    }

    /**
     * 首页城市下拉。路径写成 "/shops/cities" 而不是新开一个前缀：Spring 的 PathPattern 比较器
     * 会把字面量段排在 "{id}" 之前，所以它不会被下面那条 /shops/{id} 抢走（Long 转换也接不住 "cities"）。
     */
    @GetMapping("/shops/cities")
    public Result<List<String>> cities() {
        return Result.ok(shopService.cities());
    }

    /**
     * 商家下拉选项（只有 id + name）。给发帖页的「关联商家」用。
     * 和 /shops/cities 同样的路径理由：字面量段排在 "{id}" 之前，不会被详情抢走。
     * 上限 200 条在 Mapper 里，前端拿到 200 条会提示"只列出了前 200 家"，不装作拿全了。
     */
    @GetMapping("/shops/options")
    public Result<List<OptionVO>> options() {
        return Result.ok(shopService.options());
    }

    @GetMapping("/shops/{id}")
    public Result<ShopVO> detail(@PathVariable Long id) {
        return Result.ok(shopService.detail(id));
    }
}
