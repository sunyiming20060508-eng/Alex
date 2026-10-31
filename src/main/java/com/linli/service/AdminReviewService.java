package com.linli.service;

import com.linli.common.PageResult;
import com.linli.dto.AdminReviewQueryDTO;
import com.linli.vo.AdminVOs;

/**
 * 管理端的评价审核。一期只有"通过 / 驳回"两个动作，不做改评价、不做回复：
 * 评价是用户写的，平台改了它就不可信了；要回复是阶段 6 的商家客服话题。
 */
public interface AdminReviewService {

    PageResult<AdminVOs.ReviewRow> page(AdminReviewQueryDTO query);

    /**
     * 审核一条评价。
     *
     * @param status 1 恢复通过（展示且计入展示分）、2 驳回（不展示且不计入展示分）
     */
    AdminVOs.StatusResult audit(Long id, Integer status);
}
