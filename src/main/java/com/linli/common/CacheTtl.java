package com.linli.common;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 缓存类 key 的 TTL 抖动（防雪崩）。
 *
 * <p><b>要解决的问题</b>：同一批 key 用同一个写死 TTL，就会<b>同一秒一起过期</b>。
 * 最典型的场景是压测或发版后批量预热——500 家商家详情一次性灌进 Redis、TTL 都是 1800 秒，
 * 1800 秒之后那一秒全部失效，而那一秒的所有请求一起回库。这才是雪崩的形态 2
 * （形态 1 是 Redis 整个挂掉，那个只能靠集群和降级，不是抖动能救的）。
 *
 * <p><b>为什么按比例而不是固定值</b>：固定加 0~300 秒听起来通用，但对 600 秒的分类列表
 * 等于把寿命随机成 600~900 秒（+50%），那不叫抖动叫"寿命随机化"，缓存行为变得没法预期；
 * 而对 30 天的 Feed 收件箱，300 秒的抖动又几乎等于没有。所以取<b>基数的 10%</b>，
 * 再给一个 <b>30 秒下限</b>（基数很小时——比如 120 秒的空值标记——按比例只有 12 秒，
 * 短于一次常规预热周期，散不开）。
 *
 * <p><b>只给缓存用，业务有效期绝不许走这里</b>：验证码 5 分钟就是 5 分钟，
 * 下单幂等键 30 分钟就是 30 分钟，发码冷却 60 秒就是 60 秒，重建锁 5 秒就是 5 秒。
 * 那些时长是<b>语义</b>不是<b>寿命估计</b>，抖一下就是给用户"有时候验证码 5 分半还没过期"
 * 或者"锁偶尔提前失效"这类查不出来的问题。所以这里刻意不叫 {@code ttl(int)}，
 * 叫 {@code CacheTtl.of(...)}，让调用点自己说明白它是不是缓存。
 */
public final class CacheTtl {

    /** 抖动幅度：基数的 10%。 */
    static final double RATIO = 0.10d;

    /** 抖动下限：基数太小时按比例会抖到没意义，兜一个 30 秒。 */
    static final int MIN_JITTER_SECONDS = 30;

    private CacheTtl() {
    }

    /**
     * @param baseSeconds 配置里的基准 TTL，抖动只会<b>往上加</b>，不会缩短——
     *                    缩短等于把缓存提前作废，那和雪崩是同一个方向的错误
     * @return 基准 + [0, max(30, 基准×10%)] 秒
     */
    public static Duration of(int baseSeconds) {
        return Duration.ofSeconds(baseSeconds + jitterSeconds(baseSeconds));
    }

    /** 抖动窗口（不含基准本身）。测试的边界断言用的就是它，所以规则只有一份。 */
    static int maxJitterSeconds(int baseSeconds) {
        return Math.max(MIN_JITTER_SECONDS, (int) (baseSeconds * RATIO));
    }

    static int jitterSeconds(int baseSeconds) {
        return ThreadLocalRandom.current().nextInt(maxJitterSeconds(baseSeconds) + 1);
    }
}
