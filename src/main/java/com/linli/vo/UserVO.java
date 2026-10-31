package com.linli.vo;

import com.linli.common.ImageVariant;
import com.linli.common.TimeText;
import com.linli.entity.User;
import java.time.LocalDateTime;

/**
 * 用户资料返回对象。手机号默认脱敏，完整手机号不返回给普通页面（API_CONTRACT.md 第 4 节）；
 * 时间按契约统一成 yyyy-MM-dd HH:mm:ss，不直接把 LocalDateTime.toString() 吐给前端。
 * gender 是 V5 迁移补上的列（0 保密、1 男、2 女），历史账号读出来就是默认 0。
 * hasPassword 只回答"这个账号设过密码没有"，让前端在首次验证码登录后弹窗引导设置；哈希本身不出接口。
 * avatarThumb 是头像的小规格地址（圆圈只有 64 CSS px，没必要下载原图），从 avatar 派生，不是另一份数据。
 * admin 是 user.role 的是非题（V14 加的列）。用户端 SPA 已经不再读它——管理界面按产品边界从用户端拆了出去，
 * 字段留着是给以后的独立管理端用的，而**能不能调 /api/admin/** 始终由服务端的 @RequiresAdmin 判**。
 */
public record UserVO(Long id, String phoneMask, String nickname, String avatar, String avatarThumb,
                     Integer gender, Boolean hasPassword, String createdAt, Boolean admin) {

    public static UserVO from(User user) {
        if (user == null) return null;
        return new UserVO(user.getId(), mask(user.getPhone()), user.getNickName(), user.getIcon(),
                ImageVariant.avatarThumb(user.getIcon()),
                user.getGender(), Boolean.TRUE.equals(user.getHasPassword()),
                TimeText.format(user.getCreateTime()), Integer.valueOf(1).equals(user.getRole()));
    }

    private static String mask(String phone) {
        if (phone == null || phone.length() < 11) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
