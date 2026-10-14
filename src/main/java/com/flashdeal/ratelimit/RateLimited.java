package com.flashdeal.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 限流注解：标注在 Controller 方法或类上。
 *
 * <p>两种维度可独立开关：</p>
 * <ul>
 *   <li>{@code globalLimit}：接口级配额，保护下游；</li>
 *   <li>{@code userLimit}：用户级配额，防止单用户刷接口。</li>
 * </ul>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    /** 接口级每秒配额；-1 表示用配置里的默认值，0 表示不限制。 */
    long globalLimit() default -1;

    /** 单用户每秒配额；-1 表示用配置里的默认值，0 表示不限制。 */
    long userLimit() default -1;

    /** 限流 key 前缀，默认用方法签名。 */
    String key() default "";
}
