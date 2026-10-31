package com.linli.config;

import com.linli.constant.AuthConstant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 需要 @Bean 工厂方法的公共组件（和苍穹外卖把 SmsClient 放 BeanConfiguration 是同一思路）。
 *
 * PasswordEncoder 用 BCrypt 而不是 MD5/SHA-256+盐：BCrypt 自带随机盐、可调工作因子，
 * 同一个密码两次哈希结果不同，撞库和彩虹表都不成立。这里只引 spring-security-crypto，
 * 不引 starter-security，所以这条 bean 不参与任何过滤器链，鉴权仍然是拦截器那一套。
 */
@Configuration
public class BeanConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(AuthConstant.BCRYPT_STRENGTH);
    }
}
