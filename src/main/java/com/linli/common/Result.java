package com.linli.common;

/**
 * 目标接口统一响应体：{ "code": 0, "data": {}, "msg": "ok" }
 * 兼容接口（/user、/shop、/file）暂时仍返回 success/data，见 API_CONTRACT.md 第 3 节。
 */
public record Result<T>(int code, T data, String msg) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS, data, "ok");
    }

    public static <T> Result<T> fail(int code, String msg) {
        return new Result<>(code, null, msg);
    }
}
