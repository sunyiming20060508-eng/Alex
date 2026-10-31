package com.linli.service.impl;

import com.linli.mapper.GoodsMapper;
import com.linli.service.GoodsService;
import com.linli.vo.GoodsVO;
import java.util.List;
import org.springframework.stereotype.Service;

/** 套餐列表：现阶段 16 条数据、查询简单，不缓存；等有真实读压力再按 P1 那套加空值缓存。 */
@Service
public class GoodsServiceImpl implements GoodsService {

    private final GoodsMapper goodsMapper;

    public GoodsServiceImpl(GoodsMapper goodsMapper) { this.goodsMapper = goodsMapper; }

    @Override
    public List<GoodsVO> listByShop(Long shopId) {
        if (shopId == null) return List.of();
        return goodsMapper.listOnSaleByShop(shopId).stream().map(GoodsVO::from).toList();
    }
}
