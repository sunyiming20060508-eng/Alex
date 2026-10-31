package com.linli.service;

import com.linli.vo.GoodsVO;
import java.util.List;

/** 团购套餐查询。下单入口在商家详情页，只需要按商家列出在售套餐。 */
public interface GoodsService {

    List<GoodsVO> listByShop(Long shopId);
}
