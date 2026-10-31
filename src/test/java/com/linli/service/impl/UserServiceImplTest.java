package com.linli.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linli.common.ApiException;
import com.linli.common.OwnImageGuard;
import com.linli.common.UploadUrl;
import com.linli.dto.ProfileForm;
import com.linli.entity.User;
import com.linli.mapper.UserMapper;
import com.linli.vo.UserVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * 取用户与资料修改的单元测试。这里守的两样东西都没有别的层兜着：
 * 同一手机号并发首撞唯一索引时不该冒 500，头像只能指向本平台自己上传目录（否则用户能把头像
 * 设成任意外部 URL，图片追踪和内网探测都从这个口子走）。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    private static final String PHONE = "13800138000";

    @Mock private UserMapper userMapper;

    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        // 头像白名单规则和笔记图片共用同一个 Guard，这里用真实现而不是 mock：
        // 要验的正是这条前缀规则本身，mock 掉就等于把结论当前提。
        service = new UserServiceImpl(userMapper, new OwnImageGuard(UploadUrl.fixed("/api/file/images")));
    }

    @Test
    @DisplayName("已注册的手机号直接读出来")
    void existingUserIsReturned() {
        when(userMapper.getByPhone(PHONE)).thenReturn(user(4L, PHONE));

        assertThat(service.requireByPhone(PHONE).getId()).isEqualTo(4L);
    }

    @Test
    @DisplayName("新手机号验证码登录自动建号，建出来的账号没有密码")
    void newUserIsRereadAfterInsert() {
        when(userMapper.getByPhone(PHONE)).thenReturn(null, user(9L, PHONE));

        User created = service.loginOrCreate(PHONE);

        assertThat(created.getId()).isEqualTo(9L);
        ArgumentCaptor<User> inserted = ArgumentCaptor.captor();
        verify(userMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getPhone()).isEqualTo(PHONE);
        assertThat(inserted.getValue().getPassword()).isNull();
    }

    @Test
    @DisplayName("并发首登撞唯一索引：不是异常，改读另一个线程刚插的那份")
    void concurrentFirstLoginFallsBackToRead() {
        when(userMapper.getByPhone(PHONE)).thenReturn(null, user(11L, PHONE));
        when(userMapper.insert(any())).thenThrow(new DuplicateKeyException("uk_phone"));

        assertThat(service.loginOrCreate(PHONE).getId()).isEqualTo(11L);
    }

    @Test
    @DisplayName("已注册的手机号直接读出来，不再插一遍")
    void existingUserIsReusedByLogin() {
        when(userMapper.getByPhone(PHONE)).thenReturn(user(4L, PHONE));

        assertThat(service.loginOrCreate(PHONE).getId()).isEqualTo(4L);
        verify(userMapper, never()).insert(any());
    }

    @Test
    @DisplayName("requireByPhone 遇到没注册的手机号：40401 让去注册")
    void unregisteredPhoneIsNotFound() {
        when(userMapper.getByPhone(PHONE)).thenReturn(null);

        assertFails(() -> service.requireByPhone(PHONE), 40401, "尚未注册");
    }

    @Test
    @DisplayName("手机号为空同样按未注册处理，不拿 null 去查库")
    void nullPhoneIsNotFound() {
        assertFails(() -> service.requireByPhone(null), 40401, "尚未注册");
        assertFails(() -> service.requireByPhone(""), 40401, "尚未注册");
        verify(userMapper, never()).getByPhone(any());
    }

    @Test
    @DisplayName("头像白名单：自家目标路径和兼容路径放行，外部地址一律 40001 且不写库")
    void avatarMustBeOwnUpload() {
        when(userMapper.getById(4L)).thenReturn(user(4L, PHONE));
        when(userMapper.updateProfile(any())).thenReturn(1);

        assertUpdateRejectsAvatar("https://evil.example.com/a.png");
        assertUpdateRejectsAvatar("//evil.example.com/a.png");
        assertUpdateRejectsAvatar("javascript:alert(1)");
        assertUpdateRejectsAvatar("/uploads/a.png");
        verify(userMapper, never()).updateProfile(any());

        service.updateProfile(4L, new ProfileForm(null, "/api/file/images/2026-10-25/uuid.png", null));
        service.updateProfile(4L, new ProfileForm(null, "/file/images/2026-10-25/legacy.png", null));
        verify(userMapper, times(2)).updateProfile(any());
    }

    @Test
    @DisplayName("传了昵称不能是纯空格：静默忽略会让用户以为改成功了")
    void blankNicknameIsRejectedNotIgnored() {
        when(userMapper.getById(4L)).thenReturn(user(4L, PHONE));

        assertFails(() -> service.updateProfile(4L, new ProfileForm("   ", null, null)),
                40001, "昵称长度需为 1-20 个字符");
        verify(userMapper, never()).updateProfile(any());
    }

    @Test
    @DisplayName("昵称写入前 trim，头像按原样存")
    void nicknameIsTrimmedBeforeWrite() {
        when(userMapper.getById(4L)).thenReturn(user(4L, PHONE));
        when(userMapper.updateProfile(any())).thenReturn(1);

        service.updateProfile(4L, new ProfileForm("  小明  ", "/api/file/images/2026-10-25/a.png", null));

        ArgumentCaptor<User> patch = ArgumentCaptor.captor();
        verify(userMapper).updateProfile(patch.capture());
        assertThat(patch.getValue().getNickName()).isEqualTo("小明");
        assertThat(patch.getValue().getIcon()).isEqualTo("/api/file/images/2026-10-25/a.png");
        assertThat(patch.getValue().getId()).isEqualTo(4L);
    }

    @Test
    @DisplayName("什么都没传就当不改，空更新也要回一份当前资料")
    void emptyUpdateIsANoOp() {
        when(userMapper.getById(4L)).thenReturn(user(4L, PHONE));

        UserVO vo = service.updateProfile(4L, new ProfileForm(null, null, null));

        assertThat(vo.nickname()).isEqualTo("邻居4");
        verify(userMapper, never()).updateProfile(any());
    }

    @Test
    @DisplayName("改别人的 id 查不到行就是 40401，不给\"更新成功\"的假象")
    void missingUserIsNotFound() {
        when(userMapper.getById(404L)).thenReturn(null);

        assertFails(() -> service.updateProfile(404L, new ProfileForm("小明", null, null)), 40401, "用户不存在");
        assertFails(() -> service.profile(null), 40401, "用户不存在");
    }

    @Test
    @DisplayName("对外只给脱敏手机号，完整号码不出现在 VO 里")
    void phoneMaskedInResponse() {
        when(userMapper.getById(4L)).thenReturn(user(4L, PHONE));

        UserVO vo = service.profile(4L);

        assertThat(vo.phoneMask()).isEqualTo("138****8000");
        assertThat(vo.toString()).doesNotContain(PHONE);
    }

    @Test
    @DisplayName("OSS 头像给两个规格，emoji 头像两个字段同值：前端拿哪个都不会空")
    void avatarThumbDerivedForOssOnly() {
        User withPhoto = user(4L, PHONE);
        withPhoto.setIcon("https://javaaidemon.oss-cn-beijing.aliyuncs.com/avatar/2026-10-26/a.jpg");
        when(userMapper.getById(4L)).thenReturn(withPhoto);

        UserVO vo = service.profile(4L);

        assertThat(vo.avatar()).isEqualTo(withPhoto.getIcon());
        assertThat(vo.avatarThumb()).isEqualTo(withPhoto.getIcon()
                + "?x-oss-process=image/resize,m_fill,w_128,h_128/quality,q_80");

        withPhoto.setIcon("🍵");

        assertThat(service.profile(4L).avatarThumb()).isEqualTo("🍵");
    }

    private void assertUpdateRejectsAvatar(String avatar) {
        assertFails(() -> service.updateProfile(4L, new ProfileForm(null, avatar, null)),
                40001, "头像只能使用本平台上传的图片地址");
    }

    private static User user(long id, String phone) {
        User user = new User();
        user.setId(id);
        user.setPhone(phone);
        user.setNickName("邻居" + id);
        return user;
    }

    private static void assertFails(Runnable call, int code, String messagePart) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code))
                .hasMessageContaining(messagePart);
    }
}
