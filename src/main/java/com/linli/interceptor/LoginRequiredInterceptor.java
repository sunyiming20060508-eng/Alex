package com.linli.interceptor;

import com.linli.annotation.RequiresAdmin;
import com.linli.annotation.RequiresLogin;
import com.linli.common.ApiException;
import com.linli.common.ErrorCode;
import com.linli.context.LoginUser;
import com.linli.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.annotation.Annotation;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 双拦截器的第二层：只拦带 {@link RequiresLogin} 或 {@link RequiresAdmin} 的接口。
 * 未登录抛 40101、登录了但不是管理员抛 40301，都由 CommonExceptionHandler 转成统一响应体
 * （前者带 HTTP 401，前端 request 拦截器据此清理本地登录态；后者带 403，不清登录态）。
 *
 * handler 不是 HandlerMethod（静态资源、上传目录的 ResourceHttpRequestHandler 等）时直接放行：
 * 浏览器的 img 请求不会带 Authorization 头，误判会让头像显示不出来。
 */
public class LoginRequiredInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) return true;
        boolean admin = has(method, RequiresAdmin.class);
        if (!admin && !has(method, RequiresLogin.class)) return true;
        // @RequiresAdmin 隐含要求登录：先问"是谁"，再问"是谁里的哪一种"，两件事的错误码不能混
        LoginUser user = UserContext.require();
        if (admin && !user.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "没有管理权限");
        }
        return true;
    }

    /** 方法上的注解优先，没有再看类上的；类上标注等于给这个 Controller 的每个接口都上锁。 */
    private static boolean has(HandlerMethod method, Class<? extends Annotation> type) {
        return method.getMethodAnnotation(type) != null || method.getBeanType().getAnnotation(type) != null;
    }
}
