package com.linli.service;

import com.linli.context.LoginUser;
import com.linli.dto.LoginForm;
import com.linli.dto.PasswordForm;
import com.linli.dto.PhoneChangeForm;
import com.linli.dto.PasswordLoginForm;
import com.linli.dto.RegisterForm;
import com.linli.vo.LoginResult;

/**
 * 登录态服务：验证码、注册、两种登录、令牌解析、退出、改密码。业务规则都在这层，
 * Controller 只做参数接收和响应封装。用户资料的读写在 UserService，两者不要混。
 */
public interface AuthService {

    /** 发送（开发环境是打印）验证码，5 分钟有效。登录和注册共用这一份码，强度也一样。 */
    void sendCode(String phone);

    /**
     * 验证码登录；未注册的手机号自动建号（无密码）。
     * 返回体里的 {@code user.hasPassword=false} 就是"第一次登录"，前端据此弹窗要求设置密码。
     */
    LoginResult login(LoginForm form);

    /**
     * 注册：验证码证明手机号是自己的 + 设置密码，手机号唯一索引保证一人一号。
     * 成功即登录，省一次输入。
     */
    LoginResult register(RegisterForm form);

    /** 密码登录；连续错 {@code AuthConstant.PASSWORD_MAX_FAILS} 次锁一段时间。 */
    LoginResult loginByPassword(PasswordLoginForm form);

    /** 设置或修改当前账号的密码；已设过密码的必须带原密码。 */
    void updatePassword(Long userId, PasswordForm form);

    /**
     * 换绑手机号：新号验证码证明"新号是我的" + 登录密码证明"账号是我的"，两样都要。
     *
     * <p>成功之后**当前令牌立即作废**——换绑是一次敏感变更，让旧会话继续有效等于
     * "攻击者改完手机号还能接着用原来那个 token 操作"。前端拿到 200 后要清本地登录态并要求重登。
     */
    void rebindPhone(Long userId, PhoneChangeForm form);

    /**
     * 令牌换登录用户；缺失、过期或无效一律返回 null，由拦截器决定是拒绝还是按匿名处理。
     * 换成签名 JWT 时只需要改实现类，调用方拿到的是同一个 LoginUser。
     */
    LoginUser resolve(String authorization);

    /** 退出登录：删除 Redis 会话，重复调用不报错。 */
    void logout(String authorization);
}
