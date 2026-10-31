package com.linli.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外统一入口的体检接口。真正的探测由 actuator 的 {@link HealthEndpoint} 做（会连 MySQL 和 Redis），
 * 这里只是把它的答案换个路径转述一遍，好让前端和 Nginx 只用记一个 {@code /api/health}。
 *
 * 之前这个接口是硬编码返回 UP 的，数据库挂了它也报 UP —— 上周登录 500 排查时就吃过这个亏。
 * 依赖不健康时这里返回 HTTP 503，容器编排可以直接拿它当就绪探针。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final HealthEndpoint healthEndpoint;

    public HealthController(HealthEndpoint healthEndpoint) { this.healthEndpoint = healthEndpoint; }

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        HealthComponent health = healthEndpoint.health();
        boolean up = Status.UP.getCode().equals(health.getStatus().getCode());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", health.getStatus().getCode());
        body.put("service", "life-service-api");
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
