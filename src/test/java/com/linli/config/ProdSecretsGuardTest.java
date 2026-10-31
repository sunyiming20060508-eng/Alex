package com.linli.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * prod 启动校验的用例。重点是**空字符串也要拦**：`${VAR:?}` 只拦"未定义"，
 * 空值是"定义了但是没用"，那才是线上最常见的静默错配（setx 写了空串、CI 变量没赋值）。
 */
class ProdSecretsGuardTest {

    @Test
    @DisplayName("两个密码都在：什么都不做")
    void startsWithBothSecrets() {
        assertThatCode(() -> new ProdSecretsGuard("real-db-pwd", "real-redis-pwd")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("数据库密码是空串：启动失败并且点名 MYSQL_PASSWORD")
    void blankDatasourcePasswordFailsFast() {
        assertThatThrownBy(() -> new ProdSecretsGuard("", "real-redis-pwd"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MYSQL_PASSWORD");
    }

    @Test
    @DisplayName("纯空格也算空：不把空格当密码用")
    void whitespaceCountsAsMissing() {
        assertThatThrownBy(() -> new ProdSecretsGuard("   ", "real-redis-pwd"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MYSQL_PASSWORD");
    }

    @Test
    @DisplayName("Redis 密码为空：点名 REDIS_PASSWORD，不笼统报「配置有误」")
    void blankRedisPasswordNamesTheVariable() {
        assertThatThrownBy(() -> new ProdSecretsGuard("real-db-pwd", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REDIS_PASSWORD");
    }
}
