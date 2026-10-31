package com.linli.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 每个响应的可缓存性由**应用自己声明**，网关照这个声明执行。
 *
 * <p>这是多级缓存里最容易做错的一环。URL 白名单写在 Nginx 配置里也能跑，但它和接口是**两个文件**：
 * 有人给 {@code /api/shops/{id}} 加了一个按人的字段（"我收藏了吗"），改的是 Java，
 * Nginx 那边一行都不用动、也不会报错——缓存照旧命中，于是 A 用户的收藏状态被发给 B 用户。
 * **规则和数据分家，就一定会漂移。** 所以这条过滤器把"能不能缓存"放回离数据最近的地方，
 * Nginx 只负责执行（它读上游的 {@code Cache-Control}，{@code no-store}/{@code private} 就不存）。
 *
 * <p>三档口径：
 * <ol>
 *   <li><b>匿名 + GET/HEAD + 公共只读白名单</b> → {@code public, max-age=15}。
 *       15 秒不是我拍的：它比一次首页并发的时间长大一点，又短到"运营改了东西"在界面上最多滞后 15 秒。
 *       首页、发现页、商家详情、笔记列表这类"所有人看到的一样"的数据走这一档。</li>
 *   <li><b>上传的图片</b> → {@code public, max-age=30天, immutable}。
 *       文件名是 UUID，**改名即改 URL**，所以可以放心让浏览器永久缓存；
 *       这一档反而是全站最值钱的——头像和探店图不缓存，每个首页都要重新拉一遍。</li>
 *   <li><b>其余全部</b> → {@code private, no-store}。带令牌的、POST/PUT/DELETE 的、
 *       个人数据（订单、券包、消息、收藏、地址、签到、积分）、鉴权接口、管理端，全在这一档。</li>
 * </ol>
 *
 * <p><b>为什么带令牌必须落到 no-store，而不是"缓存但按人分 key"</b>：
 * ① 这些响应里有按人字段（{@code favorite}/{@code liked}/{@code received}），共享一份缓存就是串号；
 * ② 更要紧的是**副作用**——商家详情会记一条浏览记录，网关命中意味着应用根本没跑，
 * "我看过这家店"就悄悄丢了。真实项目里更细的做法是把按人字段拆成单独接口
 * （{@code /api/shops/{id}/favorite-state}）让主体响应可缓存，我这里直接用绕过解决。
 *
 * <p>用 {@code setHeader} 而不是 {@code addHeader}：保证响应里只有一个 {@code Cache-Control}，
 * 出现两个同名头时不同中间件的取法不一样（有的取第一个、有的合并），等于埋一个说不清的行为。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)   // 紧跟在 TraceIdFilter 后面：越靠前越不容易被后面的处理器覆盖
public class CacheDirectiveFilter extends OncePerRequestFilter {

    /** 网关和浏览器都按这个秒数缓存公共只读接口。 */
    static final int PUBLIC_API_MAX_AGE_SECONDS = 15;

    /** 上传图片：文件名带 UUID，改名即改 URL，所以敢给 30 天。 */
    static final int UPLOAD_IMAGE_MAX_AGE_SECONDS = 30 * 24 * 3600;

    static final String PUBLIC_API = "public, max-age=" + PUBLIC_API_MAX_AGE_SECONDS;
    static final String IMMUTABLE_IMAGE = "public, max-age=" + UPLOAD_IMAGE_MAX_AGE_SECONDS + ", immutable";
    static final String NO_STORE = "private, no-store";

    /** 只放"任何人看到都一样、且不产生副作用"的读接口。加接口前先想清楚这两条。 */
    private static final List<Pattern> PUBLIC_API_PATHS = List.of(
            Pattern.compile("^/api/categories$"),
            Pattern.compile("^/api/shops$"),
            Pattern.compile("^/api/shops/cities$"),
            Pattern.compile("^/api/shops/options$"),
            Pattern.compile("^/api/shops/\\d+$"),
            Pattern.compile("^/api/shops/\\d+/goods$"),
            Pattern.compile("^/api/shops/\\d+/reviews$"),
            Pattern.compile("^/api/shops/\\d+/coupons$"),
            Pattern.compile("^/api/blogs$"),
            Pattern.compile("^/api/blogs/\\d+$"),
            Pattern.compile("^/api/blogs/\\d+/comments$"),
            // 券中心：登录时会多带一个按人的 received 标记，所以只有匿名请求能进这一档（下面的令牌判断兜住）
            Pattern.compile("^/api/coupons$"));

    /** 兼容路径 /file/images/... 也在内：历史头像地址走的是它。 */
    private static final List<Pattern> UPLOAD_IMAGE_PATHS = List.of(
            Pattern.compile("^/api/file/images/.+"),
            Pattern.compile("^/file/images/.+"));

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    jakarta.servlet.FilterChain chain) throws IOException, jakarta.servlet.ServletException {
        response.setHeader("Cache-Control", directiveFor(request));
        chain.doFilter(request, response);
    }

    /** 包内可见：测试直接喂请求进来断言，不用起容器。 */
    String directiveFor(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            return NO_STORE;
        }
        String path = request.getRequestURI();
        if (matches(UPLOAD_IMAGE_PATHS, path)) {
            return IMMUTABLE_IMAGE;
        }
        if (!isAnonymous(request)) {
            return NO_STORE;
        }
        return matches(PUBLIC_API_PATHS, path) ? PUBLIC_API : NO_STORE;
    }

    private static boolean isAnonymous(HttpServletRequest request) {
        // 只认"这个头不存在"。存在但为空串/全空格也算带令牌：那是客户端把 Authorization 传歪了，
        // 鉴权那边会判 401，缓存这边保守处理成不缓存——**宁可少缓存，也不能把按人的响应共享出去**。
        // （`getHeader` 对"有头但值为空"返回 ""，不是 null，正好用上。）
        return request.getHeader("Authorization") == null;
    }

    private static boolean matches(List<Pattern> patterns, String path) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }
}
