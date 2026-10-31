package com.linli.controller.user;

import com.linli.annotation.RequiresLogin;
import com.linli.common.Result;
import com.linli.constant.RedisKeyConstant;
import com.linli.context.UserContext;
import com.linli.dto.LoginForm;
import com.linli.dto.PasswordLoginForm;
import com.linli.dto.RegisterForm;
import com.linli.service.AuthService;
import com.linli.service.UserService;
import com.linli.vo.LoginResult;
import com.linli.vo.UserVO;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 目标认证接口：验证码登录、密码登录、注册、退出。响应统一成 { code, data, msg }。
 * 三种进门的方式返回的是同一种会话令牌，后续鉴权链路分不出区别。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @PostMapping("/code")
    public Result<Map<String, Object>> sendCode(@RequestBody Map<String, String> body) {
        authService.sendCode(body == null ? null : body.get("phone"));
        return Result.ok(Map.of("expiresIn", RedisKeyConstant.AUTH_CODE_TTL_SECONDS));
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginForm form) {
        return Result.ok(session(authService.login(form)));
    }

    /** 密码登录；账号没设过密码时提示改用验证码，不假装"密码不对"。 */
    @PostMapping("/login/password")
    public Result<Map<String, Object>> loginByPassword(@Valid @RequestBody PasswordLoginForm form) {
        return Result.ok(session(authService.loginByPassword(form)));
    }

    /** 注册即登录：手机号 + 验证码 + 密码，手机号唯一索引保证一人一号。 */
    @PostMapping("/register")
    public Result<Map<String, Object>> register(@Valid @RequestBody RegisterForm form) {
        return Result.ok(session(authService.register(form)));
    }

    private Map<String, Object> session(LoginResult result) {
        UserVO user = userService.profile(result.userId());
        return Map.of("token", result.token(), "user", user);
    }

    @PostMapping("/logout")
    @RequiresLogin
    public Result<Void> logout() {
        authService.logout(UserContext.require().token());
        return Result.ok(null);
    }

    /** DELETE 别名，方便后续按 REST 习惯收敛；行为与 POST /logout 一致。 */
    @DeleteMapping("/logout")
    @RequiresLogin
    public Result<Void> logoutByDelete() {
        authService.logout(UserContext.require().token());
        return Result.ok(null);
    }
}
