package com.linli.dto;

/** POST /api/auth/login 请求体。开发环境验证码在后端控制台打印，不接短信服务。 */
public record LoginForm(String phone, String code) {}
