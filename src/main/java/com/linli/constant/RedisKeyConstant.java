package com.linli.constant;

/**
 * Redis key 统一出口，命名口径对齐 IMPLEMENTATION_ROADMAP.md 第 4 节（life:{模块}:{业务}:{标识}）。
 * 之前散落在各 Service 里的 login:code:、user:phone: 等前缀全部收到这里，
 * 换 key 只改这一个类，也避免同一个业务在两个地方写出不同前缀。
 */
public final class RedisKeyConstant {

    /** 登录验证码，值为 6 位数字。 */
    public static final String AUTH_CODE_PREFIX = "life:auth:code:";

    /** 会话令牌，哈希存 userId / phone / role / issuedAt。不是签名 JWT。 */
    public static final String AUTH_TOKEN_PREFIX = "life:auth:token:";

    /**
     * 一个用户名下所有在册令牌的索引（Set，成员是 token）。
     *
     * 存在的唯一理由：**"改凭证要能把人踢下线"需要反向查**——只有 token→user 的正向哈希时，
     * 改了密码也不知道该删哪些令牌。成员可能因为滑动续期到期而变僵尸，这不影响正确性：
     * 踢人时对每个成员做一次 DEL，不存在的键就是 no-op。索引自身的 TTL 和令牌绝对上限对齐，
     * 长期不登录的人不会留下一个永久的空 Set。
     */
    public static final String AUTH_SESSION_INDEX_PREFIX = "life:auth:sessions:";

    /** 手机号到用户 id 的缓存，登录时顺带写入。 */
    public static final String USER_PHONE_PREFIX = "life:user:phone:";

    /** 商家详情缓存，值为 Shop 实体的 JSON。 */
    public static final String SHOP_DETAIL_PREFIX = "life:shop:detail:";

    /** 分类列表缓存，值为 Category 列表的 JSON。 */
    public static final String CATEGORY_LIST = "life:shop:categories";

    /** 同一手机号的发码冷却，存在即拒绝再次发送。 */
    public static final String AUTH_SEND_COOLDOWN_PREFIX = "life:auth:send:cd:";

    /** 同一手机号 24 小时内的发码次数，防短信轰炸。 */
    public static final String AUTH_SEND_DAILY_PREFIX = "life:auth:send:count:";

    /** 同一手机号当前验证码的连续校验失败次数，超限即作废验证码。 */
    public static final String AUTH_FAIL_PREFIX = "life:auth:fail:";

    /** 同一手机号密码登录的连续失败次数，超限锁一段时间（防在线爆破）。 */
    public static final String AUTH_PWD_FAIL_PREFIX = "life:auth:pwd:fail:";

    /** 券的剩余库存（领取快速通道）。权威值在 coupon.stock，这个键丢了能从数据库重建。 */
    public static final String COUPON_STOCK_PREFIX = "life:coupon:stock:";

    /** 已领过某张券的用户集合，用 SISMEMBER 做一人一券的 O(1) 判重。 */
    public static final String COUPON_CLAIMED_PREFIX = "life:coupon:claimed:";

    /**
     * 关注 Feed 的收件箱（ZSet，member=笔记 id，score=发布时间戳）。发布时推给每个粉丝一份，
     * 读的时候只做一次 ZREVRANGEBYSCORE，不再 join follow 和 blog。
     */
    public static final String FEED_FOLLOW_PREFIX = "life:feed:follow:";

    /**
     * "这个人的关注 Feed 重建过但确实是空的"占位键。没有它，一个没关注任何人的人每次翻 Feed
     * 都要重跑一遍 follow + blog 查询——和商家详情的空值缓存是同一个思路。
     */
    public static final String FEED_EMPTY_PREFIX = "life:feed:empty:";

    /** 收件箱只留最近这么多条：Feed 是翻着看的，留几年的成本没人付。 */
    public static final int FOLLOW_FEED_MAX = 200;

    /** 收件箱存活时间；到期后由下一次读取回源重建。 */
    public static final int FOLLOW_FEED_TTL_SECONDS = 30 * 24 * 3600;

    /** 空 Feed 占位只留 1 分钟：关注了新人之后最多等一分钟就能看到，不必手动清。 */
    public static final int FEED_EMPTY_TTL_SECONDS = 60;

    /** 商家详情缓存里"这个 id 不存在"的占位值，防穿透；用空串表示。 */
    public static final String CACHE_NULL_VALUE = "";
    public static final int SHOP_DETAIL_NULL_TTL_SECONDS = 120;

    /**
     * 下单幂等键：{@code life:order:req:{userId}:{requestId}}，值先是 PENDING，
     * 成功后换成订单 id。客户端生成 requestId，超时重试复用同一个，就不会再扣一次库存。
     */
    public static final String ORDER_REQUEST_PREFIX = "life:order:req:";
    public static final String ORDER_REQUEST_PENDING = "PENDING";
    public static final int ORDER_REQUEST_TTL_SECONDS = 1800;

    /** 缓存未命中时的重建锁后缀，防击穿：同一时刻只有一个请求去查库。 */
    public static final String CACHE_LOCK_SUFFIX = ":lock";
    public static final int CACHE_LOCK_TTL_SECONDS = 5;

    public static final int AUTH_CODE_TTL_SECONDS = 300;
    public static final int AUTH_TOKEN_TTL_SECONDS = 1800;

    /**
     * 令牌的**绝对**上限：滑动续期（AUTH_TOKEN_TTL_SECONDS）只解决"用到一半被踢"，
     * 只有滑动没有绝对上限就等于"被盗的令牌只要一直在活动就永远不死"。
     * 7 天是本地生活场景的舒适区上限——再长就该走"重新登录"而不是"一直续"。
     */
    public static final int AUTH_TOKEN_ABSOLUTE_TTL_SECONDS = 7 * 24 * 3600;
    public static final int USER_PHONE_TTL_SECONDS = 7200;
    public static final int SHOP_DETAIL_TTL_SECONDS = 1800;
    public static final int CATEGORY_LIST_TTL_SECONDS = 600;

    /** 两次发码的最小间隔。 */
    public static final int AUTH_SEND_COOLDOWN_SECONDS = 60;

    /** 24 小时窗口内最多发码次数。 */
    public static final int AUTH_SEND_DAILY_LIMIT = 10;
    public static final int AUTH_SEND_DAILY_WINDOW_SECONDS = 86400;

    /** 同一验证码允许的失败次数，超过就要重新获取。 */
    public static final int AUTH_CODE_MAX_FAILS = 5;

    /**
     * 券相关键的存活余量：预热时按"距券到期还有多少秒 + 这一余量"设 TTL，
     * 券过期后键自然消失，不留一批永久键。
     */
    public static final int COUPON_KEY_TTL_BUFFER_SECONDS = 7 * 86400;

    /**
     * 用户浏览过的商家：ZSet，score 是秒级时间戳，member 是 shopId。
     * 故意不建 MySQL 表——"最近看过"是短期便利数据，30 天后没有意义，丢了也不丢业务数据，
     * 和关注 Feed 那个 ZSet 同一个定位（Redis 只当加速层，不担正确性）。
     */
    public static final String USER_HISTORY_PREFIX = "life:user:history:";

    /**
     * 签到 Bitmap 前缀：键是 {@code life:sign:{yyyyMM}:{userId}}，offset 是"这个月的第几天"（0 起）。
     *
     * <p>黑马点评把所有用户挤进一张月表（offset = userId * 31 + 日），省键，代价是"数某个人这个月
     * 签了几天"要用 BITFIELD 取位段再在 Java 里数。这里反过来：一人一月一键，BITCOUNT 一步到位，
     * 键多但每个只有几十字节——签到这个量级下，可读性和正确性更值钱。
     */
    public static final String SIGN_MONTH_PREFIX = "life:sign:";

    /** 签到键留 62 天：跨月时"上个月签了几天"还要能答出来，多留两天兜时区。 */
    public static final int SIGN_TTL_SECONDS = 62 * 24 * 60 * 60;

    /** 一人最多留多少条：再多也没人往下翻，裁掉最老的才能保证键不会无限长。 */
    public static final int USER_HISTORY_MAX = 200;

    /** 键级 TTL：兜住"用户再也不来"那批键。每次浏览都会把窗口往后推。 */
    public static final int USER_HISTORY_TTL_SECONDS = 30 * 86400;

    private RedisKeyConstant() {}

    public static String authCode(String phone) {
        return AUTH_CODE_PREFIX + phone;
    }

    public static String authSendCooldown(String phone) {
        return AUTH_SEND_COOLDOWN_PREFIX + phone;
    }

    public static String authSendCount(String phone) {
        return AUTH_SEND_DAILY_PREFIX + phone;
    }

    public static String authFail(String phone) {
        return AUTH_FAIL_PREFIX + phone;
    }

    public static String authPasswordFail(String phone) {
        return AUTH_PWD_FAIL_PREFIX + phone;
    }

    public static String authToken(String token) {
        return AUTH_TOKEN_PREFIX + token;
    }

    /** 该用户所有在册令牌的索引键。 */
    public static String authSessions(Long userId) {
        return AUTH_SESSION_INDEX_PREFIX + userId;
    }

    public static String userPhone(String phone) {
        return USER_PHONE_PREFIX + phone;
    }

    public static String shopDetail(Long shopId) {
        return SHOP_DETAIL_PREFIX + shopId;
    }

    public static String couponStock(Long couponId) {
        return COUPON_STOCK_PREFIX + couponId;
    }

    public static String couponClaimed(Long couponId) {
        return COUPON_CLAIMED_PREFIX + couponId;
    }

    public static String orderRequest(Long userId, String requestId) {
        return ORDER_REQUEST_PREFIX + userId + ":" + requestId;
    }

    public static String followFeed(Long userId) {
        return FEED_FOLLOW_PREFIX + userId;
    }

    public static String feedEmptyMarker(Long userId) {
        return FEED_EMPTY_PREFIX + userId;
    }

    public static String userHistory(Long userId) {
        return USER_HISTORY_PREFIX + userId;
    }

    public static String signMonth(Long userId, String yyyyMM) {
        return SIGN_MONTH_PREFIX + yyyyMM + ":" + userId;
    }
}
