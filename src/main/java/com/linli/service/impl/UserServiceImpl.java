package com.linli.service.impl;

import com.linli.common.ApiException;
import com.linli.common.ErrorCode;
import com.linli.common.OwnImageGuard;
import com.linli.dto.ProfileForm;
import com.linli.entity.User;
import com.linli.mapper.UserMapper;
import com.linli.service.UserService;
import com.linli.vo.UserVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.linli.common.Text;

/** 用户资料读写，SQL 全在 UserMapper。对外一律经 UserVO 脱敏。 */
@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    /** 头像白名单规则和笔记图片共用同一个出口，见 {@link OwnImageGuard}。 */
    private final OwnImageGuard imageGuard;

    public UserServiceImpl(UserMapper userMapper, OwnImageGuard imageGuard) {
        this.userMapper = userMapper;
        this.imageGuard = imageGuard;
    }

    @Override
    @Transactional
    public User loginOrCreate(String phone) {
        User user = userMapper.getByPhone(phone);
        if (user != null) return user;
        User created = new User();
        created.setPhone(phone);
        try {
            userMapper.insert(created);
        } catch (DuplicateKeyException e) {
            // 同一手机号并发首登：另一个线程已经插进去了，直接读它那份，不该冒成 500
            return requireByPhone(phone);
        }
        // 表里有唯一索引，插入成功后重新查一次，拿到昵称、创建时间这些数据库默认值
        return requireByPhone(phone);
    }

    @Override
    public User requireByPhone(String phone) {
        // 空白手机号直接按未注册处理，不拿它去数据库绕一圈
        User user = (phone == null || phone.isBlank()) ? null : userMapper.getByPhone(phone);
        if (user == null) throw new ApiException(ErrorCode.NOT_FOUND, "该手机号尚未注册，请先注册");
        return user;
    }

    @Override
    public UserVO profile(Long userId) {
        return UserVO.from(requireUser(userId));
    }

    @Override
    @Transactional
    public UserVO updateProfile(Long userId, ProfileForm form) {
        User user = requireUser(userId);
        String nickname = form == null ? null : form.nickname();
        String icon = form == null ? null : trimToNull(form.avatar());
        Integer gender = form == null ? null : form.gender();
        // 昵称传了空串或全空格视为非法输入，而不是"不更新"，避免前端改了却没反应
        if (nickname != null && nickname.trim().isEmpty()) {
            throw new ApiException(ErrorCode.PARAM_INVALID, "昵称长度需为 1-20 个字符");
        }
        nickname = trimToNull(nickname);
        if (icon != null && !imageGuard.matches(icon)) {
            throw new ApiException(ErrorCode.PARAM_INVALID, "头像只能使用本平台上传的图片地址");
        }
        // gender 传 0 是"改成保密"这个真实意图，不能和"没传"混为一谈，所以只判 null
        if (nickname == null && icon == null && gender == null) return UserVO.from(user);

        User patch = new User();
        patch.setId(user.getId());
        patch.setNickName(nickname);
        patch.setIcon(icon);
        patch.setGender(gender);
        userMapper.updateProfile(patch);
        return UserVO.from(requireUser(userId));
    }

    private User requireUser(Long userId) {
        User user = userId == null ? null : userMapper.getById(userId);
        if (user == null) throw new ApiException(ErrorCode.NOT_FOUND, "用户不存在");
        return user;
    }

    private static String trimToNull(String value) {
        return Text.trimToNull(value);
    }
}
