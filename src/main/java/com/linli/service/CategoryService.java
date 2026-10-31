package com.linli.service;

import com.linli.vo.CategoryVO;
import java.util.List;

/** 分类服务，供发现页筛选条和 /api/shops 的 categoryId 使用。 */
public interface CategoryService {

    List<CategoryVO> list();
}
