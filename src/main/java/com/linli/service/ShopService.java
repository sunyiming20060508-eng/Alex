package com.linli.service;

import com.linli.common.PageResult;
import com.linli.dto.ShopPageQueryDTO;
import com.linli.vo.OptionVO;
import com.linli.vo.ShopVO;
import java.math.BigDecimal;
import java.util.List;

/** 商家服务：阶段 2 起数据源是 shop 表，详情走 Redis 缓存旁路。 */
public interface ShopService {

    PageResult<ShopVO> page(ShopPageQueryDTO query);

    /** 商家详情；不存在时按 40401「商家不存在」抛出，前端据此显示空态。 */
    ShopVO detail(Long id);

    /** 首页城市下拉：库里真有哪些城市就给哪些。 */
    List<String> cities();

    /**
     * 商家下拉：只有 id 和 name（发帖页「关联商家」用）。
     * 不读缓存也不写缓存——它和 /shops 列表不是一份数据形状，缓存键共用只会让两边互相失效。
     */
    List<OptionVO> options();

    /**
     * 重算商家展示分。缓存归这一层管，所以写分数必须走这里而不是让评价那边自己去删 key——
     * 这是本项目第一个会影响 shop 列的写路径，忘了失效的话新分数要等 30 分钟 TTL 才看得见。
     */
    void refreshScore(Long shopId, BigDecimal score);
}
