package com.linli.config;

import com.linli.interceptor.LoginContextInterceptor;
import com.linli.interceptor.LoginRequiredInterceptor;
import com.linli.service.AuthService;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 总配置：跨域、登录拦截器、上传目录的静态映射。和苍穹外卖一样集中在一个
 * WebMvcConfiguration 里，避免"到底哪个 config 生效"的问题。
 *
 * 拦截器顺序固定：先解析上下文（order 1），再判断是否必须登录（order 2）。
 * 第二层现在注册在 /** 上但只拦带 @RequiresLogin 的接口，所以新增受保护接口不用再回来改这里；
 * 静态图片（/api/file/images/**、/file/images/**）由非 HandlerMethod 的处理器承接，天然不会被拦。
 */
@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    private final AuthService authService;
    private final String uploadDir;
    private final String corsAllowedOrigins;

    public WebMvcConfiguration(AuthService authService,
                               @Value("${linli.upload.dir:uploads}") String uploadDir,
                               @Value("${linli.cors.allowed-origins:}") String corsAllowedOrigins) {
        this.authService = authService;
        this.uploadDir = uploadDir;
        this.corsAllowedOrigins = corsAllowedOrigins;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginContextInterceptor(authService))
                // /user/** 和 /shop/** 的兼容 Controller 已下线，这里只留目标路径和还要解析上下文的图片路径
                .addPathPatterns("/api/**", "/file/**")
                .order(1);
        registry.addInterceptor(new LoginRequiredInterceptor())
                .addPathPatterns("/**")
                .order(2);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 目标路径和兼容路径指向同一个目录，旧前端引用的图片地址不会因为换接口而失效
        String location = "file:" + Paths.get(uploadDir).toAbsolutePath().normalize() + "/";
        registry.addResourceHandler("/api/file/images/**", "/file/images/**").addResourceLocations(location);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 生产是 Nginx 同源，不需要跨域；只有 Vite 开发服务器要，配置留空就不注册。
        if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) return;
        registry.addMapping("/**")
                .allowedOrigins(corsAllowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
