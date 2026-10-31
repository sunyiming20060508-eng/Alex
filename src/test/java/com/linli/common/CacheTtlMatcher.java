package com.linli.common;

import java.time.Duration;
import org.mockito.ArgumentMatchers;

/**
 * 测试用的 TTL 匹配器：断言"这个 TTL 落在基准加抖动的区间里"。
 *
 * <p>为什么需要它：TTL 一旦带上抖动，{@code eq(Duration.ofSeconds(1800))} 这种钉死单个值的断言
 * 就全错了——而且**钉死了就等于把随机性测没了**（把抖动删掉也能过）。区间断言才是新契约。
 * 上限直接取 {@link CacheTtl#maxJitterSeconds}，规则和实现同一份，不会两边各写一个 10% 然后漂移。
 */
public final class CacheTtlMatcher {

    private CacheTtlMatcher() {
    }

    public static Duration jittered(int baseSeconds) {
        long max = (long) baseSeconds + CacheTtl.maxJitterSeconds(baseSeconds);
        return ArgumentMatchers.argThat(ttl -> ttl != null
                && ttl.getSeconds() >= baseSeconds && ttl.getSeconds() <= max);
    }
}
