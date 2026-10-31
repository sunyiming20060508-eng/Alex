package com.linli.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 接口层统一时间格式（API_CONTRACT.md 第 2 节：{@code yyyy-MM-dd HH:mm:ss}）。
 * 由 UserVO / CouponVO / MyCouponVO 共用，避免每个 VO 各写一个 formatter 后格式漂移。
 */
public final class TimeText {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TimeText() {}

    public static String format(LocalDateTime value) {
        return value == null ? null : value.format(TS);
    }
}
