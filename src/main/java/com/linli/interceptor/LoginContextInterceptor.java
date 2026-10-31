package com.linli.interceptor;

import com.linli.context.LoginUser;
import com.linli.context.UserContext;
import com.linli.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 双拦截器的第一层：只负责"能解析就放进上下文"，不判断登录与否。
 * 这样公开接口（商家列表、详情）在带令牌时也能拿到用户信息，匿名请求一次 Redis 都不查。
 */
public class LoginContextInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    public LoginContextInterceptor(AuthService authService) { this.authService = authService; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        LoginUser user = authService.resolve(request.getHeader("Authorization"));
        if (user != null) UserContext.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
