package com.linli.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 可缓存性声明的单元测试。这一组断言的是**边界**，不是格式：
 * 哪一类响应允许被网关/浏览器缓存、哪一类必须禁止。写错方向的代价是不报错的
 * ——A 用户的收藏状态发给 B 用户，或者"我看过这家店"被网关命中吞掉，都查不出来。
 */
class CacheDirectiveFilterTest {

    private final CacheDirectiveFilter filter = new CacheDirectiveFilter();

    private String directive(String method, String path, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return filter.directiveFor(request);
    }

    @Test
    @DisplayName("匿名的公共读：可以缓存，15 秒")
    void anonymousPublicReadIsCacheable() {
        assertThat(directive("GET", "/api/categories", null)).isEqualTo("public, max-age=15");
        assertThat(directive("GET", "/api/shops", null)).isEqualTo("public, max-age=15");
        assertThat(directive("GET", "/api/shops/12", null)).isEqualTo("public, max-age=15");
        assertThat(directive("GET", "/api/shops/12/reviews", null)).isEqualTo("public, max-age=15");
        assertThat(directive("GET", "/api/blogs/3", null)).isEqualTo("public, max-age=15");
    }

    @Test
    @DisplayName("带令牌一律 no-store：按人字段会串号，且详情接口有副作用（记浏览记录）")
    void anyTokenDisablesCaching() {
        assertThat(directive("GET", "/api/categories", "Bearer abc")).isEqualTo("private, no-store");
        assertThat(directive("GET", "/api/shops/12", "Bearer abc")).isEqualTo("private, no-store");
        // 空串和只有空格都算"没带令牌"？不算——拦的是"这个请求声称自己是登录用户"，
        // 空值交给鉴权拦截器判 401，这里保守处理成不缓存更安全
        assertThat(directive("GET", "/api/categories", "   ")).isEqualTo("private, no-store");
    }

    @Test
    @DisplayName("个人数据即使在 GET 上也不许缓存：订单、券包、消息、收藏、地址、我的内容、管理端")
    void personalDataNeverCaches() {
        for (String path : List.of("/api/orders", "/api/coupons/my", "/api/notifications", "/api/user/me",
                "/api/user/favorites", "/api/user/me/addresses", "/api/user/me/blogs", "/api/admin/blogs",
                "/api/user/me/sign/status", "/api/user/me/points", "/api/user/history")) {
            assertThat(directive("GET", path, null)).as(path).isEqualTo("private, no-store");
        }
    }

    @Test
    @DisplayName("非 GET/HEAD 永远 no-store；关注流那条路径不能被笔记详情的正则顺手放进来")
    void writesAndLookalikesStayUncached() {
        assertThat(directive("POST", "/api/blogs", null)).isEqualTo("private, no-store");
        assertThat(directive("DELETE", "/api/user/me/blogs/9", "Bearer abc")).isEqualTo("private, no-store");
        // /api/blogs/feed/follow 是"按人"的流，但形状上像 /api/blogs/{id}——这条断言防的就是正则放太宽
        assertThat(directive("GET", "/api/blogs/feed/follow", null)).isEqualTo("private, no-store");
        assertThat(directive("GET", "/api/shops/abc", null)).isEqualTo("private, no-store");
    }

    @Test
    @DisplayName("上传的图片：文件名是 UUID，改名即改 URL，所以给 30 天 immutable（含历史兼容路径）")
    void uploadedImagesAreImmutable() {
        assertThat(directive("GET", "/api/file/images/blog/2026-10-27/3f2a.png", null))
                .isEqualTo("public, max-age=2592000, immutable");
        assertThat(directive("GET", "/file/images/avatar/2026-10-26/9b1c.png", null))
                .isEqualTo("public, max-age=2592000, immutable");
    }

    @Test
    @DisplayName("响应里只有一个 Cache-Control：用 setHeader 不是 addHeader")
    void headerIsWrittenOnce() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/categories");
        request.setRequestURI("/api/categories");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            // 下游什么都不做：只验证头是在进业务之前就已经定下来了
        });

        List<String> values = response.getHeaders("Cache-Control").stream().toList();
        assertThat(values).containsExactly("public, max-age=15");
    }
}
