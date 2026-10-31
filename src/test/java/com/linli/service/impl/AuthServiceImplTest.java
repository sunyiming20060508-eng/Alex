package com.linli.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.linli.common.ApiException;
import com.linli.constant.AuthConstant;
import com.linli.constant.RedisKeyConstant;
import com.linli.context.LoginUser;
import com.linli.context.UserContext;
import com.linli.dto.LoginForm;
import com.linli.dto.PasswordForm;
import com.linli.dto.PasswordLoginForm;
import com.linli.dto.RegisterForm;
import com.linli.entity.User;
import com.linli.mapper.UserMapper;
import com.linli.service.UserService;
import com.linli.vo.LoginResult;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 发码限流与登录的单元测试。Redis 用 mock，是为了把"额度用尽""失败次数""滑动续期"这些
 * 只有靠计数才能触发的分支跑出来——接口实测里要等 60 秒冷却才能看到的分支，这里一次就够。
 *
 * 并发首登撞唯一索引那条路属于 UserServiceImpl，不在这里测。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final String PHONE = "13800138000";
    private static final String CODE_KEY = RedisKeyConstant.authCode(PHONE);
    private static final String FAIL_KEY = RedisKeyConstant.authFail(PHONE);

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;
    @Mock private HashOperations<String, Object, Object> hashes;
    @Mock private SetOperations<String, String> sets;
    @Mock private UserService userService;
    @Mock private UserMapper userMapper;

    /**
     * 用真的 BCrypt 实现而不是 mock：这一批用例要证明的就是"存进去的不是明文、比对能通过"，
     * mock 掉编码器等于把要验的东西假设成对。强度调到 4（最快档），单测不该为安全余量等时间。
     */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(redis, userService, userMapper, passwordEncoder);
        // 签发会话要往"这个人名下有哪些令牌"的索引里登记一次，几乎每个用例都会路过；
        // 用 lenient 是因为发码/校验那几条路径根本不走 Set，严格桩会反过来报"多余打桩"。
        lenient().when(redis.opsForSet()).thenReturn(sets);
    }

    @Test
    @DisplayName("手机号格式不对就到此为止，一次 Redis 都不碰——否则给别人做号码探针")
    void malformedPhoneNeverReachesRedis() {
        for (String bad : new String[]{null, "", "1380013800", "23800138000", "13800138000a"}) {
            assertFails(() -> service.sendCode(bad), 40001, "手机号格式不正确");
            assertFails(() -> service.login(new LoginForm(bad, "123456")), 40001, "手机号格式不正确");
        }
        // 断言"一个数据结构都没碰"：模板本身在 setUp 里被 lenient 桩摸过一次，
        // 所以这里看的是值/哈希/集合三种操作，这才是这条用例真正要保护的东西
        verifyNoInteractions(values, hashes, sets);
    }

    @Test
    @DisplayName("60 秒冷却靠 setIfAbsent 一次往返完成：并发点两次只会有一个成功")
    void sendCodeCooldown() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq(RedisKeyConstant.authSendCooldown(PHONE)), anyString(), any(Duration.class)))
                .thenReturn(false);

        assertFails(() -> service.sendCode(PHONE), 40901, "发送过于频繁");
        verify(values, never()).set(eq(CODE_KEY), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("单日发送上限：第 11 次拒绝，第 10 次还放行")
    void sendCodeDailyCap() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        String countKey = RedisKeyConstant.authSendCount(PHONE);
        when(values.increment(countKey)).thenReturn(10L, 11L);

        service.sendCode(PHONE);
        assertFails(() -> service.sendCode(PHONE), 40901, "上限");
    }

    @Test
    @DisplayName("验证码是 6 位数字、带 TTL，并且给这一份新码一份新的失败额度")
    void sendCodeWritesSixDigitCodeWithTtl() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(values.increment(anyString())).thenReturn(1L);

        service.sendCode(PHONE);

        ArgumentCaptor<String> code = ArgumentCaptor.captor();
        verify(values).set(eq(CODE_KEY), code.capture(),
                eq(Duration.ofSeconds(RedisKeyConstant.AUTH_CODE_TTL_SECONDS)));
        assertThat(code.getValue()).matches("^\\d{6}$");
        // 第一次发送要把 24 小时窗口挂到计数键上，否则这个键永久驻留
        verify(redis).expire(RedisKeyConstant.authSendCount(PHONE),
                Duration.ofSeconds(RedisKeyConstant.AUTH_SEND_DAILY_WINDOW_SECONDS));
        verify(redis).delete(FAIL_KEY);
    }

    @Test
    @DisplayName("失败次数用满就先删码再拒绝：留着码，攻击者还能接着穷举")
    void loginBlocksAfterMaxFails() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(FAIL_KEY)).thenReturn(String.valueOf(RedisKeyConstant.AUTH_CODE_MAX_FAILS));

        assertFails(() -> service.login(new LoginForm(PHONE, "123456")), 40001, "验证码已失效");
        verify(redis).delete(CODE_KEY);
        verify(redis).delete(FAIL_KEY);
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("计数键里是脏值时按 0 处理，顺手把脏键删掉，不让用户永远登不进去")
    void unparsableFailCounterIsReset() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(FAIL_KEY)).thenReturn("abc");
        when(values.get(CODE_KEY)).thenReturn("123456");

        assertFails(() -> service.login(new LoginForm(PHONE, "000000")), 40001, "验证码错误或已过期");
        verify(redis).delete(FAIL_KEY);
    }

    @Test
    @DisplayName("码错只记一次失败，回的是同一句话——不泄露这个号有没有注册过")
    void loginWithWrongCodeRecordsFailure() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(FAIL_KEY)).thenReturn(null);
        when(values.get(CODE_KEY)).thenReturn("123456");
        when(values.increment(FAIL_KEY)).thenReturn(1L);

        assertFails(() -> service.login(new LoginForm(PHONE, "000000")), 40001, "验证码错误或已过期");
        verify(values).increment(FAIL_KEY);
        verify(redis).expire(eq(FAIL_KEY), any(Duration.class));
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("登录成功：令牌写进 Redis、验证码和失败计数都清掉、手机号反查缓存补上")
    void loginSuccessIssuesToken() {
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHash()).thenReturn(hashes);
        when(values.get(FAIL_KEY)).thenReturn(null);
        when(values.get(CODE_KEY)).thenReturn("123456");
        when(userService.loginOrCreate(PHONE)).thenReturn(user(4L));

        LoginResult result = service.login(new LoginForm(PHONE, "123456"));

        assertThat(result.userId()).isEqualTo(4L);
        assertThat(result.token()).matches("^[0-9a-f]{32}$");
        String tokenKey = RedisKeyConstant.authToken(result.token());
        verify(hashes).put(tokenKey, "userId", "4");
        verify(hashes).put(tokenKey, "phone", PHONE);
        verify(redis).expire(eq(tokenKey), eq(Duration.ofSeconds(RedisKeyConstant.AUTH_TOKEN_TTL_SECONDS)));
        verify(redis).delete(CODE_KEY);
        verify(redis).delete(FAIL_KEY);
        verify(values).set(eq(RedisKeyConstant.userPhone(PHONE)), eq("4"), any(Duration.class));
    }

    @Test
    @DisplayName("会话解析一次 HGETALL 取回 userId 和 phone，并顺手滑动续期")
    void resolveRefreshesTokenTtl() {
        when(redis.opsForHash()).thenReturn(hashes);
        String token = "0123456789abcdef0123456789abcdef";
        Map<Object, Object> session = new LinkedHashMap<>();
        session.put("userId", "4");
        session.put("phone", PHONE);
        when(hashes.entries(RedisKeyConstant.authToken(token))).thenReturn(session);

        LoginUser user = service.resolve("Bearer " + token);

        assertThat(user.userId()).isEqualTo(4L);
        assertThat(user.phone()).isEqualTo(PHONE);
        assertThat(user.token()).isEqualTo(token);
        verify(redis).expire(RedisKeyConstant.authToken(token),
                Duration.ofSeconds(RedisKeyConstant.AUTH_TOKEN_TTL_SECONDS));
    }

    @Test
    @DisplayName("会话里带上身份：/api/admin/** 每次判断只看已经读出来的这一份，不回库查 role")
    void sessionCarriesRole() {
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHash()).thenReturn(hashes);
        when(values.get(FAIL_KEY)).thenReturn(null);
        when(values.get(CODE_KEY)).thenReturn("123456");
        User admin = user(4L);
        admin.setRole(1);
        when(userService.loginOrCreate(PHONE)).thenReturn(admin);

        LoginResult result = service.login(new LoginForm(PHONE, "123456"));

        verify(hashes).put(RedisKeyConstant.authToken(result.token()), "role", "1");
    }

    @Test
    @DisplayName("解析会话带出身份；改动前签发的老会话没有这一列、或值不是数字，都按普通用户算")
    void resolveReadsRoleAndDefaultsToUser() {
        when(redis.opsForHash()).thenReturn(hashes);
        String token = "aabbccddeeff00112233445566778899";
        String key = RedisKeyConstant.authToken(token);
        Map<Object, Object> session = new LinkedHashMap<>();
        session.put("userId", "4");
        session.put("role", "1");
        when(hashes.entries(key)).thenReturn(session);
        assertThat(service.resolve("Bearer " + token).isAdmin()).isTrue();

        // 权限这件事上猜高了比猜低了危险：宁可让他重新登录一次才能进管理端
        Map<Object, Object> legacy = new LinkedHashMap<>();
        legacy.put("userId", "4");
        when(hashes.entries(key)).thenReturn(legacy);
        assertThat(service.resolve("Bearer " + token).role()).isZero();

        legacy.put("role", "not-a-number");
        assertThat(service.resolve("Bearer " + token).role()).isZero();
    }

    @Test
    @DisplayName("没有令牌、令牌解不出 userId，都当作未登录且不续期")
    void resolveWithoutValidToken() {
        for (String header : new String[]{null, "", "   ", "Bearer "}) {
            assertThat(service.resolve(header)).isNull();
        }
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(anyString())).thenReturn(Map.of());

        assertThat(service.resolve("deadbeef")).isNull();
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("退出登录删掉令牌并从在册索引里摘掉；没带令牌时一次 Redis 写都不做")
    void logoutDeletesToken() {
        String token = "0123456789abcdef0123456789abcdef";

        service.logout(null);
        service.logout("Bearer ");
        verify(redis, never()).delete(anyString());

        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.get(RedisKeyConstant.authToken(token), "userId")).thenReturn("9");

        service.logout("Bearer " + token);
        verify(redis).delete(RedisKeyConstant.authToken(token));
        // 索引里的成员不摘就会一直躺着：下次"按人踢会话"要白删一遍，白名单也会越积越长
        verify(sets).remove(RedisKeyConstant.authSessions(9L), token);
    }

    @Test
    @DisplayName("注册：密码不合规不烧验证码，验证码错也不建号")
    void registerRejectsBeforeConsumingCode() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(FAIL_KEY)).thenReturn(null);

        // 弱密码（纯数字）走不到读验证码那一步
        assertFails(() -> service.register(new RegisterForm(PHONE, "123456", "12345678", null)),
                40001, "密码需 8-32 位");
        verify(values, never()).get(CODE_KEY);

        // 已存在的手机号同样不该消耗验证码
        when(userMapper.getByPhone(PHONE)).thenReturn(user(4L));
        assertFails(() -> service.register(new RegisterForm(PHONE, "123456", "abcd1234", null)),
                40901, "该手机号已注册");
        verify(values, never()).get(CODE_KEY);

        // 验证码错：不建号
        when(userMapper.getByPhone(PHONE)).thenReturn(null);
        when(values.get(CODE_KEY)).thenReturn("654321");
        when(values.increment(FAIL_KEY)).thenReturn(1L);
        assertFails(() -> service.register(new RegisterForm(PHONE, "123456", "abcd1234", null)),
                40001, "验证码错误或已过期");
        verify(userMapper, never()).insertRegistered(any());
    }

    @Test
    @DisplayName("注册成功：库里是 BCrypt 哈希不是明文，并且注册即登录")
    void registerStoresHashAndIssuesSession() {
        givenValuesAndHashes();
        when(values.get(FAIL_KEY)).thenReturn(null);
        when(values.get(CODE_KEY)).thenReturn("123456");
        when(userMapper.getByPhone(PHONE)).thenReturn(null, user(7L));

        LoginResult result = service.register(new RegisterForm(PHONE, "123456", "abcd1234", " 小明 "));

        ArgumentCaptor<User> inserted = ArgumentCaptor.captor();
        verify(userMapper).insertRegistered(inserted.capture());
        User saved = inserted.getValue();
        assertThat(saved.getPassword()).isNotEqualTo("abcd1234").startsWith("$2");
        assertThat(passwordEncoder.matches("abcd1234", saved.getPassword())).isTrue();
        // 同一个密码两次注册得到不同哈希：BCrypt 自带随机盐，撞库和彩虹表都不成立
        assertThat(saved.getPassword()).isNotEqualTo(passwordEncoder.encode("abcd1234"));
        assertThat(saved.getNickName()).isEqualTo("小明");
        assertThat(result.token()).matches("^[0-9a-f]{32}$");
        verify(redis).delete(CODE_KEY);
    }

    @Test
    @DisplayName("注册撞唯一索引（两个人同时提交同一手机号）回 40901，不冒 500")
    void registerHandlesDuplicateKey() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(FAIL_KEY)).thenReturn(null);
        when(values.get(CODE_KEY)).thenReturn("123456");
        when(userMapper.getByPhone(PHONE)).thenReturn(null);
        when(userMapper.insertRegistered(any())).thenThrow(new DuplicateKeyException("uk_phone"));

        assertFails(() -> service.register(new RegisterForm(PHONE, "123456", "abcd1234", null)),
                40901, "该手机号已注册");
    }

    @Test
    @DisplayName("密码登录：没注册和没设密码回同一句话，两者在事实层面等价")
    void passwordLoginTreatsMissingAccountLikeMissingPassword() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(RedisKeyConstant.authPasswordFail(PHONE))).thenReturn(null);
        when(userMapper.getByPhoneWithSecret(PHONE)).thenReturn(null);
        assertFails(() -> service.loginByPassword(new PasswordLoginForm(PHONE, "abcd1234")),
                40001, "还没有设置密码");

        User passwordless = user(4L);
        passwordless.setPassword(null);
        when(userMapper.getByPhoneWithSecret(PHONE)).thenReturn(passwordless);
        assertFails(() -> service.loginByPassword(new PasswordLoginForm(PHONE, "abcd1234")),
                40001, "还没有设置密码");
    }

    @Test
    @DisplayName("密码错记一次失败，并且失败计数带上了 10 分钟的锁定时长")
    void passwordLoginCountsFailures() {
        String failKey = RedisKeyConstant.authPasswordFail(PHONE);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(failKey)).thenReturn(null);
        User stored = user(4L);
        stored.setPassword(passwordEncoder.encode("abcd1234"));
        when(userMapper.getByPhoneWithSecret(PHONE)).thenReturn(stored);
        when(values.increment(failKey)).thenReturn(1L);

        assertFails(() -> service.loginByPassword(new PasswordLoginForm(PHONE, "wrong-pass")),
                40001, "手机号或密码不正确");
        verify(values).increment(failKey);
        verify(redis).expire(eq(failKey), eq(Duration.ofSeconds(AuthConstant.PASSWORD_LOCK_SECONDS)));
    }

    @Test
    @DisplayName("错满 5 次进锁定期：连库都不查，就算这次密码是对的也拒绝")
    void passwordLoginLockedAfterMaxFails() {
        String failKey = RedisKeyConstant.authPasswordFail(PHONE);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(failKey)).thenReturn(String.valueOf(AuthConstant.PASSWORD_MAX_FAILS));

        assertFails(() -> service.loginByPassword(new PasswordLoginForm(PHONE, "abcd1234")),
                40901, "密码错误次数过多");
        verify(userMapper, never()).getByPhoneWithSecret(anyString());
    }

    @Test
    @DisplayName("密码对：清掉失败计数并签发与验证码登录同款的令牌")
    void passwordLoginSuccess() {
        givenValuesAndHashes();
        String failKey = RedisKeyConstant.authPasswordFail(PHONE);
        when(values.get(failKey)).thenReturn("2");
        User stored = user(4L);
        stored.setPassword(passwordEncoder.encode("abcd1234"));
        when(userMapper.getByPhoneWithSecret(PHONE)).thenReturn(stored);

        LoginResult result = service.loginByPassword(new PasswordLoginForm(PHONE, "abcd1234"));

        assertThat(result.userId()).isEqualTo(4L);
        assertThat(result.token()).matches("^[0-9a-f]{32}$");
        verify(redis).delete(failKey);
        verify(hashes).put(RedisKeyConstant.authToken(result.token()), "userId", "4");
        verify(hashes).put(RedisKeyConstant.authToken(result.token()), "phone", PHONE);
    }

    @Test
    @DisplayName("从没设过密码的账号（存量验证码用户）可以直接设置，不需要原密码")
    void firstPasswordNeedsNoOldOne() {
        User passwordless = user(4L);
        when(userMapper.getByIdWithSecret(4L)).thenReturn(passwordless);

        service.updatePassword(4L, new PasswordForm(null, "abcd1234"));

        ArgumentCaptor<String> hash = ArgumentCaptor.captor();
        verify(userMapper).updatePassword(eq(4L), hash.capture());
        assertThat(hash.getValue()).startsWith("$2").isNotEqualTo("abcd1234");
        assertThat(passwordEncoder.matches("abcd1234", hash.getValue())).isTrue();
    }

    @Test
    @DisplayName("已设过密码：必须带对原密码，也不能和新密码一样")
    void changePasswordRequiresCorrectOldOne() {
        User withPassword = user(4L);
        withPassword.setPassword(passwordEncoder.encode("abcd1234"));
        when(userMapper.getByIdWithSecret(4L)).thenReturn(withPassword);

        assertFails(() -> service.updatePassword(4L, new PasswordForm(null, "efgh5678")),
                40001, "请填写原密码");
        assertFails(() -> service.updatePassword(4L, new PasswordForm("wrong-pass", "efgh5678")),
                40001, "原密码不正确");
        assertFails(() -> service.updatePassword(4L, new PasswordForm("abcd1234", "abcd1234")),
                40001, "不能与原密码相同");
        verify(userMapper, never()).updatePassword(any(), anyString());
    }

    @Test
    @DisplayName("改密码要能按 id 取到哈希；取不到就是用户不存在")
    void updatePasswordNeedsExistingUser() {
        when(userMapper.getByIdWithSecret(404L)).thenReturn(null);
        assertFails(() -> service.updatePassword(404L, new PasswordForm(null, "abcd1234")),
                40401, "用户不存在");
    }

    @Test
    @DisplayName("验证码登录首次进来会自动建号（无密码），hasPassword 由资料接口告诉前端")
    void loginUsesTheAutoCreatedUser() {
        givenValuesAndHashes();
        when(values.get(FAIL_KEY)).thenReturn(null);
        when(values.get(CODE_KEY)).thenReturn("123456");
        User created = user(9L);
        created.setHasPassword(false);
        when(userService.loginOrCreate(PHONE)).thenReturn(created);

        LoginResult result = service.login(new LoginForm(PHONE, "123456"));

        assertThat(result.userId()).isEqualTo(9L);
        verify(userService).loginOrCreate(PHONE);
    }

    @Test
    @DisplayName("签发会话时登记进「这个人名下有哪些令牌」：没有这份索引，改密码时无从知道该删谁")
    void issueRegistersSessionIntoPerUserIndex() {
        givenValuesAndHashes();
        when(values.get(FAIL_KEY)).thenReturn(null);
        when(values.get(CODE_KEY)).thenReturn("123456");
        when(userService.loginOrCreate(PHONE)).thenReturn(user(9L));

        LoginResult result = service.login(new LoginForm(PHONE, "123456"));

        verify(sets).add(RedisKeyConstant.authSessions(9L), result.token());
        verify(redis).expire(RedisKeyConstant.authSessions(9L),
                Duration.ofSeconds(RedisKeyConstant.AUTH_TOKEN_ABSOLUTE_TTL_SECONDS));
        verify(hashes).put(eq(RedisKeyConstant.authToken(result.token())), eq("issuedAt"), anyString());
    }

    @Test
    @DisplayName("改密码：其他设备的会话作废，当前这一次保留——首次登录后引导设密码不该把自己踢下线")
    void passwordChangeRevokesOtherSessionsButKeepsCurrent() {
        when(userMapper.getByIdWithSecret(4L)).thenReturn(rebinding(true));
        when(sets.members(RedisKeyConstant.authSessions(4L)))
                .thenReturn(new LinkedHashSet<>(List.of("tok-current", "tok-other")));
        UserContext.set(new LoginUser(4L, PHONE, "tok-current", 0));
        try {
            service.updatePassword(4L, new PasswordForm("abcd1234", "sym12345"));

            verify(userMapper).updatePassword(eq(4L), anyString());
            verify(redis).delete(RedisKeyConstant.authToken("tok-other"));
            verify(redis, never()).delete(RedisKeyConstant.authToken("tok-current"));
        } finally {
            UserContext.clear();
        }
    }

    @Test
    @DisplayName("令牌超过绝对上限：滑动 TTL 还没到也必须失效，并把自己从在册索引里摘掉")
    void absoluteExpiryKillsEvenAnActiveToken() {
        String token = "tok-ancient";
        String key = RedisKeyConstant.authToken(token);
        Map<Object, Object> session = new LinkedHashMap<>();
        session.put("userId", "4");
        session.put("phone", PHONE);
        session.put("issuedAt", String.valueOf(System.currentTimeMillis()
                - (RedisKeyConstant.AUTH_TOKEN_ABSOLUTE_TTL_SECONDS + 60) * 1000L));
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(key)).thenReturn(session);

        assertThat(service.resolve("Bearer " + token)).isNull();

        verify(redis).delete(key);
        verify(sets).remove(RedisKeyConstant.authSessions(4L), token);
        verify(redis, never()).expire(eq(key), any(Duration.class));
    }

    @Test
    @DisplayName("改动之前签发的老令牌没有 issuedAt：就地补签发时刻，而不是把已经在线的人踢掉")
    void legacyTokenIsUpgradedNotKilled() {
        String token = "tok-legacy";
        String key = RedisKeyConstant.authToken(token);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(key)).thenReturn(Map.of("userId", "4", "phone", PHONE));

        assertThat(service.resolve("Bearer " + token)).isNotNull();

        verify(hashes).put(eq(key), eq("issuedAt"), anyString());
        verify(redis, never()).delete(key);
    }

    private void givenValuesAndHashes() {
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHash()).thenReturn(hashes);
    }

    private static User user(long id) {
        User user = new User();
        user.setId(id);
        user.setPhone(PHONE);
        return user;
    }

    private static void assertFails(Runnable call, int code, String messagePart) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code))
                .hasMessageContaining(messagePart);
    }

    // ---------- 换绑手机号 ----------

    private static final String NEW_PHONE = "13900002222";

    /** 用真 BCrypt（强度 4）而不是 mock：mock 掉哈希等于把"密码对不对"这件事假设成对。 */
    private User rebinding(boolean withPassword) {
        User user = new User();
        user.setId(4L);
        user.setPhone(PHONE);
        if (withPassword) {
            user.setPassword(passwordEncoder.encode("abcd1234"));
        }
        return user;
    }

    private void givenNewPhoneCodeIs(String code) {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(RedisKeyConstant.authFail(NEW_PHONE))).thenReturn(null);
        when(values.get(RedisKeyConstant.authCode(NEW_PHONE))).thenReturn(code);
    }

    @Test
    @DisplayName("没设过密码的账号不能换绑：先用即将被换掉的东西证明身份没有意义")
    void rebindRequiresPasswordFirst() {
        when(userMapper.getByIdWithSecret(4L)).thenReturn(rebinding(false));

        assertFails(() -> service.rebindPhone(4L,
                new com.linli.dto.PhoneChangeForm(NEW_PHONE, "123456", "abcd1234")), 40001, "请先设置密码");

        verify(values, never()).get(RedisKeyConstant.authCode(NEW_PHONE));
        verify(userMapper, never()).updatePhone(any(), anyString());
    }

    @Test
    @DisplayName("密码不对就到此为止：不消耗验证码、不改手机号")
    void rebindRejectsWrongPassword() {
        when(userMapper.getByIdWithSecret(4L)).thenReturn(rebinding(true));

        assertFails(() -> service.rebindPhone(4L,
                new com.linli.dto.PhoneChangeForm(NEW_PHONE, "123456", "wrong-pass")), 40101, "登录密码不正确");

        verify(userMapper, never()).updatePhone(any(), anyString());
    }

    @Test
    @DisplayName("换绑成功：改库 + 手机号反查缓存搬到新号 + 旧令牌作废")
    void rebindMovesCacheAndKillsToken() {
        when(userMapper.getByIdWithSecret(4L)).thenReturn(rebinding(true));
        givenNewPhoneCodeIs("123456");

        service.rebindPhone(4L, new com.linli.dto.PhoneChangeForm(NEW_PHONE, "123456", "abcd1234"));

        verify(userMapper).updatePhone(4L, NEW_PHONE);
        verify(redis).delete(RedisKeyConstant.userPhone(PHONE));
        verify(values).set(eq(RedisKeyConstant.userPhone(NEW_PHONE)), eq("4"), any(Duration.class));
        verify(redis).delete(RedisKeyConstant.authCode(NEW_PHONE));
    }

    @Test
    @DisplayName("新号已被别人占用：撞唯一索引翻译成 40901，而不是 500")
    void rebindRejectsTakenPhone() {
        when(userMapper.getByIdWithSecret(4L)).thenReturn(rebinding(true));
        givenNewPhoneCodeIs("123456");
        when(userMapper.updatePhone(4L, NEW_PHONE)).thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));

        assertFails(() -> service.rebindPhone(4L,
                new com.linli.dto.PhoneChangeForm(NEW_PHONE, "123456", "abcd1234")), 40901, "已被其他账号使用");
    }

}
