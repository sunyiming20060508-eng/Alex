package com.linli.context;

import com.linli.common.ApiException;
import com.linli.common.ErrorCode;

/**
 * 请求级登录上下文。LoginContextInterceptor 在 preHandle 写入、afterCompletion 清理，
 * Controller 和 Service 从这里取当前用户，不再手工读 Authorization 头。
 * 相当于苍穹外卖的 BaseContext，只是我们只存登录用户这一件事。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> CURRENT = new ThreadLocal<>();

    private UserContext() {}

    public static void set(LoginUser user) { CURRENT.set(user); }

    public static LoginUser current() { return CURRENT.get(); }

    public static boolean isLogged() { return CURRENT.get() != null; }

    /** 未登录时按 40101 抛出，由 handler/CommonExceptionHandler 转成统一响应。 */
    public static LoginUser require() {
        LoginUser user = CURRENT.get();
        if (user == null) throw new ApiException(ErrorCode.UNAUTHORIZED, "未登录或令牌失效");
        return user;
    }

    public static Long requireUserId() { return require().userId(); }

    /** 匿名接口上"有就算、没有就 null"的取法，比如笔记列表要算我是否点过赞。 */
    public static Long userIdOrNull() {
        LoginUser user = CURRENT.get();
        return user == null ? null : user.userId();
    }

    /** 必须在 afterCompletion 调用：线程复用，残留的上下文会把上一个用户带给下一个请求。 */
    public static void clear() { CURRENT.remove(); }
}
