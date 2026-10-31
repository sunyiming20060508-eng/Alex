package com.linli.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在 Controller 类或方法上，表示该接口必须登录。由 LoginRequiredInterceptor 读
 * {@code HandlerMethod} 上的注解来决定放行还是拦截，取代之前写在 WebMvcConfiguration 里的路径清单。
 *
 * 之所以要换成注解：同一个 URL 会出现"GET /api/shops/{id} 公开、POST /api/shops/{id}/favorite 需登录"
 * 这种按方法区分的诉求，路径清单表达不了。两个参考项目都没有现成实现（苍穹的 @AutoFill 是公共字段填充，
 * 黑马只有路径白名单），所以这套是自己写的。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresLogin {
}
