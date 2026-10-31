package com.linli.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linli.common.ApiException;
import com.linli.common.PageResult;
import com.linli.dto.AdminBlogQueryDTO;
import com.linli.entity.Blog;
import com.linli.mapper.BlogMapper;
import com.linli.vo.AdminVOs;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 笔记下架的单元测试。最值钱的两条：① 非法状态必须在查库之前就被挡住（否则"传个 5"会写成脏数据）；
 * ② 状态文字由服务端给，管理台不需要再抄一份 0/1 映射。
 */
@ExtendWith(MockitoExtension.class)
class AdminBlogServiceImplTest {

    private static final Long ID = 12L;

    @Mock private BlogMapper blogMapper;

    private AdminBlogServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminBlogServiceImpl(blogMapper);
    }

    private static Blog blog(int status) {
        Blog item = new Blog();
        item.setId(ID);
        item.setUserId(2L);
        item.setShopId(1L);
        item.setTitle("糖醋排骨还是那个味");
        item.setContent("下午两点去的");
        item.setCover("🍲");
        item.setLikeCount(3);
        item.setCommentCount(1);
        item.setStatus(status);
        item.setAuthorName("阿岚");
        item.setShopName("江南小馆");
        item.setCreateTime(LocalDateTime.of(2026, 10, 26, 10, 0));
        return item;
    }

    private static AdminBlogQueryDTO query(Integer status) {
        AdminBlogQueryDTO condition = new AdminBlogQueryDTO();
        condition.setStatus(status);
        return condition;
    }

    @Test
    @DisplayName("总数为 0 时不再查列表，返回空分页而不是 [null]")
    void emptyPageSkipsListQuery() {
        when(blogMapper.countAdmin(any())).thenReturn(0L);

        PageResult<AdminVOs.BlogRow> page = service.page(query(null));

        assertThat(page.list()).isEmpty();
        assertThat(page.total()).isZero();
        verify(blogMapper, never()).pageAdmin(any());
    }

    @Test
    @DisplayName("列表行带状态文字：0 是已下架、1 是展示中")
    void mapsStatusText() {
        when(blogMapper.countAdmin(any())).thenReturn(2L);
        when(blogMapper.pageAdmin(any())).thenReturn(List.of(blog(0), blog(1)));

        PageResult<AdminVOs.BlogRow> page = service.page(query(null));

        assertThat(page.list()).extracting(AdminVOs.BlogRow::statusText)
                .containsExactly("已下架", "展示中");
        assertThat(page.list().get(0).authorName()).isEqualTo("阿岚");
        assertThat(page.list().get(0).shopName()).isEqualTo("江南小馆");
    }

    @Test
    @DisplayName("筛选状态非法时在查库之前就报错，不会把 5 写进 where 条件")
    void rejectsUnknownFilterStatus() {
        assertThatThrownBy(() -> service.page(query(5)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("status");

        verify(blogMapper, never()).countAdmin(any());
    }

    @Test
    @DisplayName("关键词两侧空格被去掉，全空串按不筛选处理")
    void trimsKeyword() {
        when(blogMapper.countAdmin(any())).thenReturn(0L);
        AdminBlogQueryDTO condition = query(null);
        condition.setKeyword("   ");

        service.page(condition);

        assertThat(condition.getKeyword()).isNull();
    }

    @Test
    @DisplayName("下架：改状态并回整行状态，幂等（对已下架的再下架一次也是同个结果）")
    void takesBlogOffline() {
        when(blogMapper.getAnyById(ID)).thenReturn(blog(1));

        AdminVOs.StatusResult result = service.updateStatus(ID, 0);

        verify(blogMapper).updateStatus(ID, 0);
        assertThat(result.status()).isZero();
        assertThat(result.statusText()).isEqualTo("已下架");
    }

    @Test
    @DisplayName("笔记不存在回 404，且不动任何行")
    void unknownBlogIsNotFound() {
        when(blogMapper.getAnyById(ID)).thenReturn(null);

        assertThatThrownBy(() -> service.updateStatus(ID, 0))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("笔记不存在");

        verify(blogMapper, never()).updateStatus(any(), any());
    }

    @Test
    @DisplayName("状态取值只认 0 和 1：传 2 会被拒，不会静默写成没人知道是什么的状态")
    void rejectsUnknownStatus() {
        assertThatThrownBy(() -> service.updateStatus(ID, 2))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("status");

        verify(blogMapper, never()).getAnyById(any());
        verify(blogMapper, never()).updateStatus(any(), any());
    }
}
