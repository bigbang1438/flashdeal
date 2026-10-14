package com.flashdeal.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.flashdeal.metrics.MetricsInterceptor;
import com.flashdeal.ratelimit.RateLimitInterceptor;

/**
 * Web 层配置：注册指标拦截器与限流拦截器、开发期跨域、静态资源同源托管。
 *
 * <p>前端构建产物（{@code frontend/dist}）在构建时被复制到
 * {@code src/main/resources/static}，因此 Spring Boot 以同源方式托管管理台，
 * 不需要再起一个前端服务器，也不会有跨域问题。</p>
 *
 * <p>拦截器顺序：限流（order=10）先于指标（order=20）执行——
 * 被限流的请求不应该计入接口延迟分位，否则 P99 会被 429 的快速返回拉低而失真。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final MetricsInterceptor metricsInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;
    private final boolean corsEnabled;

    public WebConfig(MetricsInterceptor metricsInterceptor,
                     RateLimitInterceptor rateLimitInterceptor,
                     @Value("${flashdeal.web.cors-enabled:true}") boolean corsEnabled) {
        this.metricsInterceptor = metricsInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.corsEnabled = corsEnabled;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**")
                .order(10);
        registry.addInterceptor(metricsInterceptor)
                .addPathPatterns("/api/**")
                .order(20);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (!corsEnabled) {
            return;
        }
        // 仅用于本地开发（Vite dev server 5173）；生产由 Spring Boot 同源托管，不依赖 CORS
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id", "X-RateLimit-Remaining", "Retry-After")
                .maxAge(3600);
    }
}
