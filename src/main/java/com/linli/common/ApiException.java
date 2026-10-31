package com.linli.common;

/** 带业务错误码的异常，由 CommonExceptionHandler 转成目标响应和对应 HTTP 状态。 */
public class ApiException extends RuntimeException {

    private final int code;

    public ApiException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
