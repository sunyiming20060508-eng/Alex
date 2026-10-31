package com.linli;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 邻里生活 API 入口。Mapper 用 @Mapper 注解标注，这里再统一扫描一次，
 * 保证以后新增 Mapper 不会漏掉注册。
 */
@SpringBootApplication
@MapperScan("com.linli.mapper")
@EnableScheduling
public class LifeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LifeServiceApplication.class, args);
    }
}
