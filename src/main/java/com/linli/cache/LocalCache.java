package com.linli.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 进程内一级缓存（Caffeine），挡在 Redis 二级缓存前面。
 *
 * <p><b>它省的是什么</b>：一次 Redis 往返 + 一次 Jackson 反序列化。命中 L2 时数据库已经不查了，
 * 但每个请求仍然要走一趟网络到 Memurai、把整段 JSON 读回来再解析成对象。
 * 本机 Redis 是回环、这个开销小；<b>真实部署里 Redis 在另一台机器上，这一趟是 0.5~1 ms 级</b>，
 * 而首页和详情页每次都要付。所以 L1 的收益和"Redis 离你多远"成正比，而不是和 QPS 成正比。
 *
 * <p><b>只放两类值</b>：分类列表（全局单 key）和商家详情里的 {@code Shop} 实体——
 * 共同点是<b>与访问者无关</b>。任何按人算的字段（{@code favorite}、{@code liked}）都在缓存之外补，
 * 否则会犯和"把 favorite 缓存进 L2"一样的错：第一个人的状态发给之后所有人。
 *
 * <p><b>多实例怎么办（这是必答题，答案是 TTL 而不是广播）</b>：L1 的过期时间只有
 * {@code l1-ttl-seconds}（默认 5 秒）。同一实例内的写路径会直接 {@link #invalidate} 掉，
 * 是立即生效；<b>跨实例的最坏情况是旧数据多活 5 秒</b>。要连这 5 秒都不留，标准做法是
 * Redis pub/sub 广播失效消息，每个实例收到后清自己那一份——那是<b>一整条链路</b>
 * （消息通道、订阅者、订阅者挂了怎么办、广播比写还慢的时候怎么办），
 * 而这几条 key 的语义是"运营配置和展示分"，5 秒的窗口用户看不出来。
 * <b>所以这里的取舍是：用一个短 TTL 换掉一条广播链路。</b>等到真的上多实例并且有人
 * 反馈"分改了没生效"，再把它升级成 pub/sub，接口形状不用变。
 *
 * <p><b>不缓存"不存在"</b>：空值标记（防穿透）留在 L2。L1 再存一份"这个 id 没有"，
 * 会让一个刚建出来的商家在所有实例上都被假 404 打 5 秒——穿透的账 L2 已经付过了，
 * 这里没必要再上一层风险。
 *
 * <p><b>开关 {@code linli.cache.l1-enabled}</b>：关掉之后 {@link #get} 恒返回 null、
 * {@link #put} 不写，等于退回单层 Redis。留着它有两个理由：线上出问题时可以一键关掉而不用回滚发版；
 * 以及<b>这一层的收益必须能被量出来</b>——A/B 就是靠这个开关跑的（见 docs/WORKLOG.md S-117）。
 */
@Component
public class LocalCache {

    /** 指标里这条缓存的名字：`cache_gets_total{cache="l1"}`。换名等于改面板，所以只有一个出口。 */
    public static final String METRIC_NAME = "l1";

    private final boolean enabled;
    private final Cache<String, Object> cache;

    /**
     * Spring 用这条。类上有两个构造器（下面还有一条给测试注入假时钟的），
     * <b>多构造器时必须点名一个</b>，否则容器会退回去找无参构造器——
     * 这个坑是运行时踩到的：单测全绿、`java -jar` 直接起不来，报
     * {@code Failed to instantiate [LocalCache]: No default constructor found}。
     *
     * <p>{@code registries} 用 {@link ObjectProvider} 而不是直接注入：指标注册表是 actuator 带的，
     * 万一哪天把 actuator 摘掉，缓存不该跟着起不来。
     */
    @Autowired
    public LocalCache(@Value("${linli.cache.l1-enabled:true}") boolean enabled,
                      @Value("${linli.cache.l1-max-entries:500}") int maxEntries,
                      @Value("${linli.cache.l1-ttl-seconds:5}") int ttlSeconds,
                      ObjectProvider<MeterRegistry> registries) {
        this(enabled, maxEntries, Duration.ofSeconds(Math.max(1, ttlSeconds)), Ticker.systemTicker(),
                registries.getIfAvailable());
    }

    /**
     * 测试从这条进来：塞一个假 {@link Ticker} 就能让"过期"这件事不用真等 5 秒，
     * registry 给 null 表示不绑指标（单测里想验证命中率就传一个 SimpleMeterRegistry）。
     */
    public LocalCache(boolean enabled, int maxEntries, Duration ttl, Ticker ticker, MeterRegistry registry) {
        this.enabled = enabled;
        // 统计本身很便宜（几个 LongAdder），不开的话命中率永远是"我以为很高"
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(ttl.toMillis(), TimeUnit.MILLISECONDS)
                .ticker(ticker)
                .recordStats()
                .build();
        // 关掉这一层的时候不绑：否则面板上会出现一条"永远是 0 的命中率"，比没有更误导人
        if (registry != null && enabled) {
            CaffeineCacheMetrics.monitor(registry, this.cache, METRIC_NAME);
        }
    }

    /** 关掉或者没命中都返回 null，调用方按"未命中"处理即可，不需要知道开关状态。 */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        if (!enabled) {
            return null;
        }
        return (T) cache.getIfPresent(key);
    }

    public void put(String key, Object value) {
        if (!enabled || value == null) {
            return;
        }
        cache.put(key, value);
    }

    /** 写路径用。关掉开关时也要能调，语义上就是"清一个本来就没有的东西"。 */
    public void invalidate(String key) {
        cache.invalidate(key);
    }

    public boolean enabled() {
        return enabled;
    }

    public long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }
}
