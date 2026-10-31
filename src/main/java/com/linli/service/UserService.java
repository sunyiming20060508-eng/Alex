package com.linli.service;

import com.linli.dto.ProfileForm;
import com.linli.entity.User;
import com.linli.vo.UserVO;

/** 用户资料服务。手机号是登录凭证，明文只在服务内部流转，出口统一是脱敏的 UserVO。 */
public interface UserService {

    /**
     * 按手机号取用户，不存在时建号（首次验证码登录即注册）。
     * 自动建出来的账号没有密码，前端靠 {@code UserVO.hasPassword} 识别出来并弹窗引导设置。
     */
    User loginOrCreate(String phone);

    /** 按手机号取已注册用户；不存在时按 40401 抛出（注册接口建完号回查用的就是它）。 */
    User requireByPhone(String phone);

    /** 当前用户资料；用户不存在时按 40401 抛出。 */
    UserVO profile(Long userId);

    /** 只更新传进来的字段，返回更新后的资料。 */
    UserVO updateProfile(Long userId, ProfileForm form);
}
