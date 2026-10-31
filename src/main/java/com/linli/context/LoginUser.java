package com.linli.context;

/**
 * 登录上下文中的当前用户，由 context/UserContext 按请求保存。
 * 令牌本身一并保留，退出登录需要用它删除 Redis 会话。
 * 当前令牌是 Redis 会话令牌（life:auth:token:{token}），不是签名 JWT；换成 JWT 只影响
 * AuthServiceImpl.resolve 的实现，调用方看到的都是这个记录。
 *
 * {@code role} 是登录那一刻从 user 表带进会话的，**改库不会让已经在手的令牌变身份**——
 * 要生效得重新登录。这样管理员鉴权每次请求只读一次 Redis 哈希，不必为 /api/admin/** 再查一遍库。
 */
public record LoginUser(Long userId, String phone, String token, int role) {

    public static final int ROLE_ADMIN = 1;

    /** 兼容改动前的构造点（测试里那些"只是登录、不关心身份"的场景），默认普通用户。 */
    public LoginUser(Long userId, String phone, String token) {
        this(userId, phone, token, 0);
    }

    public boolean isAdmin() {
        return role == ROLE_ADMIN;
    }
}
