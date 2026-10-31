package com.linli.common;

/** 目标接口错误码，取值见 API_CONTRACT.md 第 2 节。 */
public final class ErrorCode {

    public static final int SUCCESS = 0;
    public static final int PARAM_INVALID = 40001;
    public static final int UNAUTHORIZED = 40101;
    /** 契约里已定义但代码一直缺失，阶段 3 的方法级鉴权要用。 */
    public static final int FORBIDDEN = 40301;
    public static final int NOT_FOUND = 40401;
    /** 重复操作/库存不足，领券和下单的幂等分支要用。 */
    public static final int CONFLICT = 40901;
    public static final int SERVER_ERROR = 50001;

    private ErrorCode() {}
}
