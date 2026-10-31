package com.linli.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TTL 抖动的边界。这一类的断言只能"取很多次看范围"，不能钉死某一个值——
 * 钉死了就等于把随机性测没了（改成固定值也能过）。
 */
class CacheTtlTest {

    private static Set<Long> samples(int baseSeconds, int times) {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < times; i++) {
            seen.add(CacheTtl.of(baseSeconds).getSeconds());
        }
        return seen;
    }

    @Test
    @DisplayName("只往上加，绝不缩短：缓存提前作废和雪崩是同一个方向的错误")
    void neverShorterThanBase() {
        for (int i = 0; i < 500; i++) {
            Duration ttl = CacheTtl.of(1800);
            assertThat(ttl.getSeconds()).isBetween(1800L, 1800L + 180L);
        }
    }

    @Test
    @DisplayName("抖动幅度是基数的 10%：600 秒最多抖到 660，不是固定加 300（那是把寿命随机化）")
    void jitterIsProportional() {
        assertThat(samples(600, 500)).allMatch(v -> v >= 600 && v <= 660);
        assertThat(samples(1800, 500)).allMatch(v -> v >= 1800 && v <= 1980);
    }

    @Test
    @DisplayName("基数很小按比例会抖到没意义，所以有 30 秒下限")
    void smallBaseUsesTheFloor() {
        // 120 秒的 10% 只有 12 秒，短过一次常规预热周期，散不开 → 走 30 秒下限
        assertThat(CacheTtl.jitterSeconds(120)).isBetween(0, 30);
        assertThat(CacheTtl.jitterSeconds(600)).isBetween(0, 60);
    }

    @Test
    @DisplayName("真的在抖：500 次里至少出现 5 个不同值（写成固定值这条必红）")
    void actuallyRandomised() {
        Set<Long> seen = samples(600, 500);
        assertThat(seen.size()).isGreaterThanOrEqualTo(5);
    }
}
