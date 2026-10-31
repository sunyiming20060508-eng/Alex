package com.linli.service;

import com.linli.common.PageResult;
import com.linli.dto.ReviewForm;
import com.linli.vo.MineVOs;
import com.linli.vo.ReviewVO;

/** 商家评价。只有**自己名下已完成的订单**能评，一单一条。 */
public interface ReviewService {

    ReviewVO create(Long shopId, ReviewForm form, Long userId);

    /** 商家详情页的评价列表，匿名可看。 */
    PageResult<ReviewVO> pageByShop(Long shopId, int page, int pageSize);

    /** 我写的评价：**含被驳回的**，作者有权知道自己写过什么、现在是什么状态。 */
    PageResult<MineVOs.ReviewRow> pageMine(Long userId, int page, int pageSize);

    /**
     * 删掉自己写的评价。连带两件事：重算这家商家的展示分（只剩他这一条时保持上一次的分，不清零），
     * 以及释放 {@code uk_review_order}——删完这一单会重新变成"可评价"，这是删除应有的语义。
     */
    void deleteMine(Long userId, Long id);
}
