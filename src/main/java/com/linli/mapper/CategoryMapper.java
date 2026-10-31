package com.linli.mapper;

import com.linli.entity.Category;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** category 表数据访问。分类数量是个位数，直接全量查，缓存在 Service 层做。 */
@Mapper
public interface CategoryMapper {

    @Select("select id, name, sort, status, create_time, update_time from category where status = 1 order by sort, id")
    List<Category> listEnabled();
}
