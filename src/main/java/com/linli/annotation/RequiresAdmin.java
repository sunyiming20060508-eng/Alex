package com.linli.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在 Controller 类或方法上，表示该接口只有管理员（{@code user.role=1}）能进。
 * 由 LoginRequiredInterceptor 读 {@code HandlerMethod} 上的注解判定，和 {@link RequiresLogin} 同一套机制。
 *
 * 它**隐含要求登录**：不是管理员和没登录都进不来，所以标了它的接口不需要再标 {@code @RequiresLogin}
 * （两个都标也不冲突，检查顺序是先登录、再身份）。
 *
 * 为什么不给 /api/admin/** 单独配一个路径拦截器：那等于把"谁能进"写回配置文件里，
 * 新增一个管理接口就得多记得回来改一处——阶段 3 就是因为这个把路径清单换成了注解。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresAdmin {
}
