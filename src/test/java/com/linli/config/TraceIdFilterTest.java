package com.linli.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

/**
 * traceId 过滤器的用例。重点不在"能不能生成"，而在两条边界：
 * **MDC 必须清干净**（Tomcat 复用线程，留着就是下一个请求拿上一个人的 id），
 * 以及**外部传进来的 id 必须清洗**（含换行的 header 值能在日志里凭空造出一行伪造记录）。
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    @DisplayName("没带头就自己生成，链内能读到 MDC，链后一定清干净")
    void generatesAndAlwaysClears() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> insideChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> insideChain.set(MDC.get(TraceIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        assertThat(insideChain.get()).isNotBlank();
        assertThat(response.getHeader(TraceIdFilter.TRACE_HEADER)).isEqualTo(insideChain.get());
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).as("线程复用，MDC 不能留东西").isNull();
    }

    @Test
    @DisplayName("清洗外部 id：换行和空格一律去掉，不给日志注入留口子")
    void stripsInjectionCharacters() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_HEADER, "abc123\n2026-10-27 INFO 伪造的一行日志");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        String trace = response.getHeader(TraceIdFilter.TRACE_HEADER);
        assertThat(trace).doesNotContain("\n").doesNotContain(" ");
        assertThat(trace).startsWith("abc123");
    }

    @Test
    @DisplayName("清洗后为空（原值全是非法字符）时重新生成；超长则截到 64")
    void regeneratesWhenNothingUsable() throws Exception {
        MockHttpServletRequest blank = new MockHttpServletRequest();
        blank.addHeader(TraceIdFilter.TRACE_HEADER, "###");
        MockHttpServletResponse blankResponse = new MockHttpServletResponse();
        filter.doFilter(blank, blankResponse, (req, res) -> {
        });
        assertThat(blankResponse.getHeader(TraceIdFilter.TRACE_HEADER)).hasSize(16);

        MockHttpServletRequest longRequest = new MockHttpServletRequest();
        longRequest.addHeader(TraceIdFilter.TRACE_HEADER, "x".repeat(200));
        MockHttpServletResponse longResponse = new MockHttpServletResponse();
        filter.doFilter(longRequest, longResponse, (req, res) -> {
        });
        assertThat(longResponse.getHeader(TraceIdFilter.TRACE_HEADER)).hasSize(64);
    }

    @Test
    @DisplayName("链里抛异常也要清 MDC，并照常把 id 回写出去（异常路径最需要这条 id）")
    void clearsEvenWhenTheChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new IllegalStateException("下游炸了");
            });
        } catch (Exception ignored) {
            // 抛的是原样还是 ServletException 不重要，这条用例只关心 finally 有没有清干净
        }

        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
        assertThat(response.getHeader(TraceIdFilter.TRACE_HEADER)).isNotBlank();
    }
}
