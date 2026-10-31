package com.linli.service;

import com.linli.common.PageResult;
import com.linli.dto.AdminBlogQueryDTO;
import com.linli.vo.AdminVOs;

/**
 * 管理端的笔记处置。一期只做"下架 / 恢复"，不做删除、不做编辑：
 * 内容不是平台写的，删掉就再也拿不回来；下架是可逆的，且用户还能在自己的列表里看到它为什么没了。
 */
public interface AdminBlogService {

    PageResult<AdminVOs.BlogRow> page(AdminBlogQueryDTO query);

    /**
     * 改状态。
     *
     * @param status 0 下架、1 恢复展示；其它值按参数错误处理
     */
    AdminVOs.StatusResult updateStatus(Long id, Integer status);
}
