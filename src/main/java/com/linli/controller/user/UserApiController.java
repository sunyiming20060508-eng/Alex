package com.linli.controller.user;

import com.linli.annotation.RequiresLogin;
import com.linli.common.Result;
import com.linli.context.UserContext;
import com.linli.dto.PasswordForm;
import com.linli.dto.PhoneChangeForm;
import com.linli.dto.ProfileForm;
import com.linli.service.AuthService;
import com.linli.service.UserService;
import com.linli.vo.UserVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 目标用户接口 GET/PUT /api/user/me。登录校验由 LoginRequiredInterceptor 按类上的
 * {@link RequiresLogin} 判定，方法签名里不再出现 Authorization 头，Controller 只从 UserContext 取当前用户。
 */
@RestController
@RequestMapping("/api/user")
@RequiresLogin
public class UserApiController {

    private final UserService userService;
    private final AuthService authService;

    public UserApiController(UserService userService, AuthService authService) {
        this.userService = userService;
        this.authService = authService;
    }

    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(userService.profile(UserContext.requireUserId()));
    }

    @PutMapping("/me")
    public Result<UserVO> update(@Valid @RequestBody ProfileForm form) {
        return Result.ok(userService.updateProfile(UserContext.requireUserId(), form));
    }

    /** 设置或修改登录密码；从没设过密码的账号可以不带原密码直接设置。 */
    @PutMapping("/password")
    public Result<Void> updatePassword(@Valid @RequestBody PasswordForm form) {
        authService.updatePassword(UserContext.requireUserId(), form);
        return Result.ok(null);
    }

    /**
     * 换绑手机号：新号验证码 + 登录密码，两样都要。
     *
     * <p>成功之后**当前令牌已经被服务端作废**，所以前端必须清掉本地登录态并要求重新登录——
     * 这不是"顺手提示一下"，是这次变更的一部分：不换设备就继续用旧 token 操作，等于换绑没生效。
     */
    @PutMapping("/me/phone")
    @RequiresLogin
    public Result<Void> rebindPhone(@Valid @RequestBody PhoneChangeForm form) {
        authService.rebindPhone(UserContext.requireUserId(), form);
        return Result.ok(null);
    }
}
