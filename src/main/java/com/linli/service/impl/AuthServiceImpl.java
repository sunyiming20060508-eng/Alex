package com.linli.service.impl;

import com.linli.common.ApiException;
import com.linli.common.ErrorCode;
import com.linli.constant.AuthConstant;
import com.linli.constant.RedisKeyConstant;
import com.linli.context.LoginUser;
import com.linli.context.UserContext;
import com.linli.dto.LoginForm;
import com.linli.dto.PasswordForm;
import com.linli.dto.PhoneChangeForm;
import com.linli.dto.PasswordLoginForm;
import com.linli.dto.RegisterForm;
import com.linli.entity.User;
import com.linli.mapper.UserMapper;
import com.linli.service.AuthService;
import com.linli.service.UserService;
import com.linli.vo.LoginResult;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.linli.common.Text;

/**
 * 手机号登录：验证码登录、密码登录、注册，会话状态全在 Redis（验证码 life:auth:code:{phone}、
 * 令牌 life:auth:token:{token}）。数据库里存的是 BCrypt 哈希，明文密码不落库也不打日志。
 *
 * 三种进同一扇门的方式共用 {@link #issueSession} 发令牌，所以后续鉴权链路对它们是同一个样子。
 * 取舍：验证码登录仍然"首次自动建号"（和黑马点评一致），注册接口的真正作用是**顺手把密码设上**；
 * 存量无密码账号用密码登录时，提示走验证码登录，而不是假装"密码不对"——因为这两种情况
 * 在事实层面完全等价（这个手机号确实还没有密码），不额外泄露注册状态。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");

    /** 验证码必须用密码学安全随机源：Math.random() 是可预测的同分布序列，6 位数字撑不住穷举。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 只有 dev profile 打开：把验证码写进日志方便本地登录，生产必须关。 */
    @Value("${linli.auth.log-code:false}")
    private boolean logCode;

    private final StringRedisTemplate redis;
    private final UserService userService;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public AuthServiceImpl(StringRedisTemplate redis, UserService userService, UserMapper userMapper,
                           PasswordEncoder passwordEncoder) {
        this.redis = redis;
        this.userService = userService;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void sendCode(String phone) {
        validatePhone(phone);
        // 冷却用 setIfAbsent，一次往返同时完成"判断 + 占位"，并发点两次也不会都通过
        Boolean first = redis.opsForValue().setIfAbsent(RedisKeyConstant.authSendCooldown(phone), "1",
                Duration.ofSeconds(RedisKeyConstant.AUTH_SEND_COOLDOWN_SECONDS));
        if (!Boolean.TRUE.equals(first)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "发送过于频繁，请 " + RedisKeyConstant.AUTH_SEND_COOLDOWN_SECONDS + " 秒后再试");
        }

        String countKey = RedisKeyConstant.authSendCount(phone);
        Long sent = redis.opsForValue().increment(countKey);
        if (sent != null && sent == 1L) {
            redis.expire(countKey, Duration.ofSeconds(RedisKeyConstant.AUTH_SEND_DAILY_WINDOW_SECONDS));
        }
        if (sent != null && sent > RedisKeyConstant.AUTH_SEND_DAILY_LIMIT) {
            throw new ApiException(ErrorCode.CONFLICT, "今日验证码发送次数已达上限，请明天再试");
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redis.opsForValue().set(RedisKeyConstant.authCode(phone), code,
                Duration.ofSeconds(RedisKeyConstant.AUTH_CODE_TTL_SECONDS));
        // 新验证码对应一份新的失败额度
        redis.delete(RedisKeyConstant.authFail(phone));
        if (logCode) {
            log.info("[DEV] login code for {}: {}", phone, code);
        }
    }

    @Override
    public LoginResult login(LoginForm form) {
        if (form == null) throw new ApiException(ErrorCode.PARAM_INVALID, "请填写手机号和验证码");
        validatePhone(form.phone());
        verifyAndConsumeCode(form.phone(), form.code());
        // 有验证码就能进：没注册过的手机号在这里自动建号（无密码）。
        // 返回体里的 hasPassword=false 会让前端弹窗要求补设密码，见 LoginModal/PasswordSetupModal。
        return issueSession(userService.loginOrCreate(form.phone()));
    }

    @Override
    @Transactional
    public LoginResult register(RegisterForm form) {
        validatePhone(form.phone());
        // 先判密码强度和手机号是否已存在，再消费验证码：前两步失败不该白烧一条有额度的码
        if (form.password() == null || !form.password().matches(AuthConstant.PASSWORD_PATTERN)) {
            throw new ApiException(ErrorCode.PARAM_INVALID, AuthConstant.PASSWORD_MESSAGE);
        }
        if (userMapper.getByPhone(form.phone()) != null) {
            throw new ApiException(ErrorCode.CONFLICT, "该手机号已注册，请直接登录");
        }
        verifyAndConsumeCode(form.phone(), form.code());

        User created = new User();
        created.setPhone(form.phone());
        created.setPassword(passwordEncoder.encode(form.password()));
        String nickname = trimToNull(form.nickname());
        if (nickname != null) created.setNickName(nickname);
        try {
            userMapper.insertRegistered(created);
        } catch (DuplicateKeyException e) {
            // 两个人同时用同一个手机号注册：uk_phone 只让一个过去
            throw new ApiException(ErrorCode.CONFLICT, "该手机号已注册，请直接登录");
        }
        // 手机号只留尾号：这一行和换绑那条是同一类信息，口径必须一样（同类里出现两种严格程度，
        // 早晚会有一条被复制到别处去，然后"我们日志里不打全号"这句话就不成立了）
        log.info("注册成功: phone=尾号{} userId={}", tail(form.phone()), created.getId());
        return issueSession(userMapper.getByPhone(form.phone()));
    }

    @Override
    public LoginResult loginByPassword(PasswordLoginForm form) {
        if (form == null) throw new ApiException(ErrorCode.PARAM_INVALID, "请填写手机号和密码");
        validatePhone(form.phone());

        String failKey = RedisKeyConstant.authPasswordFail(form.phone());
        Long fails = readCount(failKey);
        if (fails != null && fails >= AuthConstant.PASSWORD_MAX_FAILS) {
            throw new ApiException(ErrorCode.CONFLICT, "密码错误次数过多，请 "
                    + AuthConstant.PASSWORD_LOCK_SECONDS / 60 + " 分钟后再试，或改用验证码登录");
        }

        User user = userMapper.getByPhoneWithSecret(form.phone());
        if (user == null || user.getPassword() == null || user.getPassword().isBlank()) {
            // 未注册和"注册了但没设密码"在事实层面是同一件事，给同一句可操作的提示
            throw new ApiException(ErrorCode.PARAM_INVALID, "该手机号还没有设置密码，请使用验证码登录");
        }
        if (!passwordEncoder.matches(form.password(), user.getPassword())) {
            recordFailure(failKey, AuthConstant.PASSWORD_LOCK_SECONDS);
            throw new ApiException(ErrorCode.PARAM_INVALID, "手机号或密码不正确");
        }

        redis.delete(failKey);
        return issueSession(user);
    }

    @Override
    @Transactional
    public void updatePassword(Long userId, PasswordForm form) {
        User user = userId == null ? null : userMapper.getByIdWithSecret(userId);
        if (user == null) throw new ApiException(ErrorCode.NOT_FOUND, "用户不存在");
        boolean hasPassword = user.getPassword() != null && !user.getPassword().isBlank();
        if (hasPassword) {
            if (trimToNull(form.oldPassword()) == null) {
                throw new ApiException(ErrorCode.PARAM_INVALID, "请填写原密码");
            }
            if (!passwordEncoder.matches(form.oldPassword(), user.getPassword())) {
                throw new ApiException(ErrorCode.PARAM_INVALID, "原密码不正确");
            }
        }
        if (hasPassword && passwordEncoder.matches(form.newPassword(), user.getPassword())) {
            throw new ApiException(ErrorCode.PARAM_INVALID, "新密码不能与原密码相同");
        }
        userMapper.updatePassword(userId, passwordEncoder.encode(form.newPassword()));
        // 凭证变了，之前发出去的会话要作废——否则"密码被盗"这件事改完密码也没有止血。
        // 但**保留当前这一次**：首次验证码登录后会引导设置密码，如果连本次会话一起踢掉，
        // 用户刚设完密码就被自己踢回登录页。要全踢是一行改动，那是产品选择不是安全底线。
        String currentToken = UserContext.isLogged() ? UserContext.require().token() : null;
        revokeOtherSessions(userId, currentToken);
        log.info("密码已更新，其他会话已作废: userId={} 首次设置={} 保留本会话={}",
                userId, !hasPassword, currentToken != null);
    }

    /**
     * 把这个人名下**除 keepToken 之外**的在册令牌全部作废。
     *
     * <p>需要反向索引（authSessions）才做得到：只有 token→user 的正向哈希时，"按人踢人"只能扫全库键名。
     * 索引成员可能已经被滑动 TTL 回收，DEL 不存在的键是 no-op，所以不需要先 EXISTS。
     */
    private void revokeOtherSessions(Long userId, String keepToken) {
        if (userId == null) return;
        Set<String> tokens = redis.opsForSet().members(RedisKeyConstant.authSessions(userId));
        if (tokens == null) return;
        tokens.stream()
                .filter(item -> item != null && !item.equals(keepToken))
                .forEach(item -> redis.delete(RedisKeyConstant.authToken(item)));
    }

    @Override
    @Transactional
    public void rebindPhone(Long userId, PhoneChangeForm form) {
        User user = userMapper.getByIdWithSecret(userId);
        if (user == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "账号不存在");
        }
        // 没设过密码的账号唯一的凭据就是原手机验证码，而换绑要换掉的正是手机号——
        // 用即将被换掉的东西证明身份没有意义，所以这条路只认密码，前端先引导去设置密码。
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw new ApiException(ErrorCode.PARAM_INVALID, "这个账号还没设置登录密码，请先设置密码再换绑手机号");
        }
        if (!passwordEncoder.matches(form.password(), user.getPassword())) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "登录密码不正确");
        }
        // 验证码证明新手机号在他手上。发码复用 POST /api/auth/code，不另开一条口子。
        verifyAndConsumeCode(form.newPhone(), form.code());
        try {
            userMapper.updatePhone(userId, form.newPhone());
        } catch (DuplicateKeyException e) {
            // 占用只在提交这一步告知，发码时不查：那样等于给任何人一个"这个号注册过没有"的探针
            throw new ApiException(ErrorCode.CONFLICT, "该手机号已被其他账号使用");
        }
        // 手机号反查缓存要跟着搬，否则 TTL 内"按手机号找人"还会指回旧号
        redis.delete(RedisKeyConstant.userPhone(user.getPhone()));
        cache(RedisKeyConstant.userPhone(form.newPhone()), String.valueOf(userId),
                RedisKeyConstant.USER_PHONE_TTL_SECONDS);
        String token = UserContext.isLogged() ? UserContext.require().token() : null;
        if (token != null) {
            redis.delete(RedisKeyConstant.authToken(token));
        }
        // 换绑改的是"登录用的那个号"，比改密码更彻底：所有设备上的会话一起作废
        revokeAllSessions(userId);
        log.info("手机号已换绑，该账号全部会话已作废: userId={} 新号尾号={}", userId, tail(form.newPhone()));
    }

    /** 日志里只留尾号：手机号是个人信息，进一次控制台就会被复制进日志文件。 */
    private static String tail(String phone) {
        return phone == null || phone.length() < 4 ? "****" : phone.substring(phone.length() - 4);
    }

    @Override
    public LoginUser resolve(String authorization) {
        String token = bareToken(authorization);
        if (token == null) return null;
        String tokenKey = RedisKeyConstant.authToken(token);
        // 一次 HGETALL 取回 userId / phone / role，不为同一个会话打两次 Redis
        Map<Object, Object> session = redis.opsForHash().entries(tokenKey);
        Object userId = session.get("userId");
        if (userId == null) return null;
        long id = Long.parseLong(userId.toString());
        // 绝对上限：滑动续期让"一直在活动"的令牌永远不死，被盗用就止不住。超过 7 天必须重新登录。
        Object issuedAt = session.get("issuedAt");
        if (issuedAt == null) {
            // 改动之前签发的老令牌没有这个字段：就地补一个签发时刻，让它从今天开始计 7 天，
            // 而不是把已经在线的人当场踢掉（升级不该是一次强制登出）
            redis.opsForHash().put(tokenKey, "issuedAt", String.valueOf(System.currentTimeMillis()));
        } else if (System.currentTimeMillis() - parseMillis(issuedAt)
                > RedisKeyConstant.AUTH_TOKEN_ABSOLUTE_TTL_SECONDS * 1000L) {
            redis.delete(tokenKey);
            redis.opsForSet().remove(RedisKeyConstant.authSessions(id), token);
            return null;
        }
        // 滑动续期：30 分钟从"登录起计时"变成"最后一次请求起计时"，
        // 用户不会在填单、选套餐填到一半时被踢掉（黑马点评的 RefreshTokenInterceptor 是同一条路）。
        redis.expire(tokenKey, Duration.ofSeconds(RedisKeyConstant.AUTH_TOKEN_TTL_SECONDS));
        Object phone = session.get("phone");
        return new LoginUser(id, phone == null ? null : phone.toString(), token, readRole(session.get("role")));
    }

    /** issuedAt 只可能是自己写进去的毫秒数字符串；读坏了就当"刚刚签发"，宁可不踢人也不误杀会话。 */
    private static long parseMillis(Object issuedAt) {
        try {
            return Long.parseLong(issuedAt.toString().trim());
        } catch (NumberFormatException e) {
            return System.currentTimeMillis();
        }
    }

    /**
     * 把这个人名下所有在册令牌一次清掉——**改了密码或换了手机号，之前发出去的会话就不该还能用**。
     *
     * <p>索引里的成员可能已经被滑动 TTL 回收，DEL 不存在的键是 no-op，所以不需要先 EXISTS。
     * 没有这层索引时"按用户踢人"只能靠扫全库键名，那是不可接受的读放大。
     */
    private void revokeAllSessions(Long userId) {
        if (userId == null) return;
        String indexKey = RedisKeyConstant.authSessions(userId);
        Set<String> tokens = redis.opsForSet().members(indexKey);
        if (tokens != null) {
            tokens.forEach(item -> redis.delete(RedisKeyConstant.authToken(item)));
        }
        redis.delete(indexKey);
    }

    /**
     * 会话里的身份。缺失（改动前签发的老令牌）或者读出来不是数字，一律按普通用户：
     * 权限这件事上**猜高了比猜低了危险**，宁可让他重新登录一次才能进管理端。
     */
    private static int readRole(Object role) {
        if (role == null) return 0;
        try {
            return Integer.parseInt(role.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void logout(String authorization) {
        String token = bareToken(authorization);
        if (token == null) return;
        // 先从哈希里拿 userId，才能把自己从在册索引里摘掉——留着僵尸成员会让下次踢人白删一遍
        Object userId = redis.opsForHash().get(RedisKeyConstant.authToken(token), "userId");
        redis.delete(RedisKeyConstant.authToken(token));
        if (userId != null) {
            try {
                redis.opsForSet().remove(RedisKeyConstant.authSessions(Long.valueOf(userId.toString())), token);
            } catch (NumberFormatException ignored) {
                // 索引键自己会过期，这里不值得为一次清理失败把登出做成 500
            }
        }
    }

    /**
     * 校验并消费验证码：一次 HGET 之外还要管失败额度，错满 {@code AUTH_CODE_MAX_FAILS} 次就把码删掉，
     * 否则攻击者还能拿同一份码继续穷举。登录和注册共用这一段，两条路的强度必须一样。
     */
    private void verifyAndConsumeCode(String phone, String code) {
        String failKey = RedisKeyConstant.authFail(phone);
        String codeKey = RedisKeyConstant.authCode(phone);
        Long fails = readCount(failKey);
        if (fails != null && fails >= RedisKeyConstant.AUTH_CODE_MAX_FAILS) {
            redis.delete(codeKey);
            redis.delete(failKey);
            throw new ApiException(ErrorCode.PARAM_INVALID, "验证码已失效，请重新获取");
        }
        String saved = redis.opsForValue().get(codeKey);
        if (saved == null || !saved.equals(code)) {
            recordFailure(failKey, RedisKeyConstant.AUTH_CODE_TTL_SECONDS);
            // 手机号不存在和验证码错误回同一句话，不泄露"这个号有没有注册过"
            throw new ApiException(ErrorCode.PARAM_INVALID, "验证码错误或已过期");
        }
        redis.delete(codeKey);
        redis.delete(failKey);
    }

    /** 签发会话令牌：三种登录方式走同一个出口，令牌结构、有效期、手机号反查缓存都一致。 */
    private LoginResult issueSession(User user) {
        String userId = String.valueOf(user.getId());
        cache(RedisKeyConstant.userPhone(user.getPhone()), userId, RedisKeyConstant.USER_PHONE_TTL_SECONDS);
        String token = UUID.randomUUID().toString().replace("-", "");
        String tokenKey = RedisKeyConstant.authToken(token);
        redis.opsForHash().put(tokenKey, "userId", userId);
        redis.opsForHash().put(tokenKey, "phone", user.getPhone());
        // 身份跟着会话走：/api/admin/** 每次判断只看已经读出来的这一份，不再回库查 role
        redis.opsForHash().put(tokenKey, "role", String.valueOf(user.getRole() == null ? 0 : user.getRole()));
        // 签发时刻单独存一份：滑动续期会一直把 TTL 往后推，没有这个字段就没人知道"这令牌最初是什么时候发的"
        redis.opsForHash().put(tokenKey, "issuedAt", String.valueOf(System.currentTimeMillis()));
        redis.expire(tokenKey, Duration.ofSeconds(RedisKeyConstant.AUTH_TOKEN_TTL_SECONDS));
        // 登记进"这个人名下有哪些令牌"，改密码/换绑时按它把人整个踢下线
        String indexKey = RedisKeyConstant.authSessions(user.getId());
        redis.opsForSet().add(indexKey, token);
        redis.expire(indexKey, Duration.ofSeconds(RedisKeyConstant.AUTH_TOKEN_ABSOLUTE_TTL_SECONDS));
        return new LoginResult(token, user.getId(), user.getPhone());
    }

    private void cache(String key, String value, int ttlSeconds) {
        redis.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds));
    }

    private Long readCount(String key) {
        String value = redis.opsForValue().get(key);
        if (value == null) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            redis.delete(key);
            return null;
        }
    }

    /** 失败计数与对应的凭证同生命周期：额度用尽前，TTL 到期即自动清零。 */
    private void recordFailure(String failKey, int ttlSeconds) {
        Long fails = redis.opsForValue().increment(failKey);
        if (fails != null && fails == 1L) {
            redis.expire(failKey, Duration.ofSeconds(ttlSeconds));
        }
    }

    private void validatePhone(String phone) {
        if (phone == null || !PHONE.matcher(phone).matches()) {
            throw new ApiException(ErrorCode.PARAM_INVALID, "手机号格式不正确");
        }
    }

    private static String trimToNull(String value) {
        return Text.trimToNull(value);
    }

    /** 兼容带 Bearer 前缀和不带的两种写法；空白一律当作未登录。 */
    private static String bareToken(String authorization) {
        if (authorization == null || authorization.isBlank()) return null;
        String value = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        return value.isBlank() ? null : value;
    }
}
