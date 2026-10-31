package com.linli.vo;

/** POST /api/auth/login 成功后的返回：会话令牌 + 用户主键 + 手机号，手机号只在服务端内部流转，不进响应体。 */
public record LoginResult(String token, Long userId, String phone) {}
