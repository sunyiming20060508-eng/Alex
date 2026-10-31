package com.linli.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 一级缓存自身的三条行为：会过期、能主动清、关掉就真的什么都不缓存。 */
class LocalCacheTest {

    /** Caffeine 的时钟是可注入的，所以"过期"这件事不用真等 5 秒。 */
    private static final class FakeTicker implements com.github.benmanes.caffeine.cache.Ticker {
        private long nanos;

        @Override
        public long read() {
            return nanos;
        }

        void advanceSeconds(long seconds) {
            nanos += Duration.ofSeconds(seconds).toNanos();
        }
    }

    private static LocalCache cache(FakeTicker ticker, int ttlSeconds) {
        return new LocalCache(true, 100, Duration.ofSeconds(ttlSeconds), ticker, null);
    }

    @Test
    @DisplayName("写进去能读到，超过 TTL 自动没了——L1 的全部意义就在这两行")
    void hitThenExpire() {
        FakeTicker ticker = new FakeTicker();
        LocalCache local = cache(ticker, 5);

        local.put("k", "v");
        assertThat((String) local.get("k")).isEqualTo("v");

        ticker.advanceSeconds(4);
        assertThat((String) local.get("k")).isEqualTo("v");

        ticker.advanceSeconds(2);
        assertThat((String) local.get("k")).isNull();
    }

    @Test
    @DisplayName("invalidate 立刻生效：这是写路径（改分、下架）能保证同实例秒级一致的那一半")
    void invalidateIsImmediate() {
        LocalCache local = cache(new FakeTicker(), 60);
        local.put("k", "v");

        local.invalidate("k");

        assertThat((String) local.get("k")).isNull();
    }

    @Test
    @DisplayName("开关关掉：读恒 null、写不进，但 invalidate 仍然可以安全调用")
    void disabledBehavesLikeNoL1() {
        LocalCache off = new LocalCache(false, 100, Duration.ofSeconds(5), new FakeTicker(), null);

        off.put("k", "v");

        assertThat(off.enabled()).isFalse();
        assertThat((String) off.get("k")).isNull();
        off.invalidate("k");
    }

    @Test
    @DisplayName("命中率是量得出来的：绑上注册表之后 hit/miss 各自计数")
    void exposesHitAndMissCounters() {
        MeterRegistry registry = new SimpleMeterRegistry();
        LocalCache local = new LocalCache(true, 100, Duration.ofSeconds(60), new FakeTicker(), registry);

        local.put("k", "v");
        local.get("k");                       // hit
        local.get("missing");                 // miss

        // micrometer 把 Caffeine 的统计绑成 **function counter**（读缓存自己的统计对象），不是它自己累加的 counter：
        // 断言时要用 functionCounter()，写成 counter() 会报 "only type found was a function counter"。
        assertThat(registry.get("cache.gets").tag("result", "hit").functionCounter().count()).isEqualTo(1.0);
        assertThat(registry.get("cache.gets").tag("result", "miss").functionCounter().count()).isEqualTo(1.0);
        // 条目数也在：面板上"命中率掉了但 cache_size 也掉了"和"命中率掉了但条目数满格"是两种完全不同的故障
        assertThat(registry.get("cache.size").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("关掉这一层就不绑指标：面板上不该出现一条永远是 0 的命中率")
    void disabledBindsNothing() {
        MeterRegistry registry = new SimpleMeterRegistry();

        new LocalCache(false, 100, Duration.ofSeconds(60), new FakeTicker(), registry);

        assertThat(registry.find("cache.gets").functionCounter()).isNull();
        assertThat(registry.find("cache.size").gauge()).isNull();
    }

    @Test
    @DisplayName("null 值不进缓存：L1 绝不存「不存在」，那层语义留在 Redis 的空值标记里")
    void nullNeverStored() {
        LocalCache local = cache(new FakeTicker(), 60);

        local.put("k", null);

        assertThat(local.size()).isZero();
    }
}
