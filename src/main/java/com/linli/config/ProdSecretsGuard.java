package com.linli.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产环境的"密钥必须真的注入"检查。
 *
 * 为什么不能只靠占位符：Spring **没有** `${VAR:?msg}` 这种"缺失就报错"的语法（那是 shell/Maven 的写法），
 * 它会把 `:?` 后面整段当成默认值——于是"变量没配"反而解析出一个**非空的垃圾密码**，应用照常启动，
 * 直到第一个请求打库才炸（本机实测踩过）。所以 prod 的 yml 写成 `${MYSQL_PASSWORD:}`（缺失即空串），
 * 由这里把"未定义"和"定义了但为空"一起当成缺失处理，让进程停在真正的原因上。
 * Hikari 默认是**懒连接**的，没有这层校验就没人会在启动时发现密码是空的。
 *
 * 与 {@link FileStorageConfiguration} 对 OSS 密钥的做法保持同一严格程度——同一件事不该一处严一处松。
 */
@Component
@Profile("prod")
public class ProdSecretsGuard {

    public ProdSecretsGuard(
            @org.springframework.beans.factory.annotation.Value("${spring.datasource.password:}") String datasourcePassword,
            @org.springframework.beans.factory.annotation.Value("${spring.data.redis.password:}") String redisPassword) {
        require(datasourcePassword, "MYSQL_PASSWORD", "数据库密码");
        require(redisPassword, "REDIS_PASSWORD", "Redis 密码");
    }

    private static void require(String value, String variable, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("生产环境必须注入非空的 " + variable + "（" + label
                    + "）；仓库里不留真实凭证，配置里的默认值只为本机开发可用");
        }
    }
}
