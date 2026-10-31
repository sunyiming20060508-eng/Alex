package com.linli.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 给每个请求一个 traceId，让"一次请求的几行日志"能被串起来看。
 *
 * 为什么值得单独一个过滤器：本项目一次请求常打三行日志（拦截器解析会话、Service 的缓存命中、
 * 写路径的成功行），没有关联 id 时线上出问题时只能靠时间戳猜哪三行是同一单。
 * 这一步**在单体里就该做**——阶段 7 拆成微服务后同一条 traceId 要跨服务透传，
 * 到那时再补，等于把所有历史日志变成不可关联的孤岛。
 *
 * 客户端可以带自己的 `X-Trace-Id`（网关或前端埋点已经在生成时复用它的 id），
 * 但**必须清洗**：只留字母数字和连字符、截到 64 字符。原因是日志注入——
 * 一个含换行的 header 值会让日志里凭空多出一行伪造记录，这是最便宜也最常见的日志投毒。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    private static final Pattern USABLE = Pattern.compile("[^A-Za-z0-9-]");
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = sanitize(request.getHeader(TRACE_HEADER));
        MDC.put(MDC_KEY, traceId);
        // 先写头再进链：进了链就可能被下游的异常处理接管，头就丢了
        response.setHeader(TRACE_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Tomcat 复用线程，不清 MDC 下一个请求会拿到上一个人的 traceId（而且线程池里永远是脏的）
            MDC.remove(MDC_KEY);
        }
    }

    private static String sanitize(String incoming) {
        if (incoming == null) return newTraceId();
        String cleaned = USABLE.matcher(incoming).replaceAll("");
        if (cleaned.isBlank()) return newTraceId();
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned;
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
