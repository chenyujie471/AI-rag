package com.example.airag.config;

import com.example.airag.auth.AdminInterceptor;
import com.example.airag.auth.AuthInterceptor;
import com.example.airag.ratelimit.RateLimitInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层装配:
 * <ol>
 *   <li>登录态校验拦截器(order=0,只保护 /api/**,放行登录、自助改密码与健康检查)</li>
 *   <li>频率限制拦截器(order=1,依赖 order=0 写入的登录用户,取不到时退化为按 IP)</li>
 *   <li>管理员校验拦截器(order=2,只管 /api/admin/**,依赖 order=0 写入的登录用户)</li>
 *   <li>跨域配置(为将来独立前端预留)</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;
    private final AdminInterceptor adminInterceptor;
    private final AppProperties properties;

    public WebConfig(AuthInterceptor authInterceptor,
                     RateLimitInterceptor rateLimitInterceptor,
                     AdminInterceptor adminInterceptor,
                     AppProperties properties) {
        this.authInterceptor = authInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.adminInterceptor = adminInterceptor;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 登录和自助改密码都必须允许"还没登录"的人访问 —— 改密码靠核对原密码来保证身份,
                // 而不是靠令牌(用户恰恰是因为密码有问题才来改的)。两个接口都被限流器压着。
                .excludePathPatterns("/api/auth/login", "/api/auth/password", "/api/health")
                .order(0);

        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**")
                .order(1);

        // 顺序很关键:必须晚于 authInterceptor,否则拿不到登录用户
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/api/admin/**")
                .order(2);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        CorsRegistration registration = registry.addMapping("/api/**")
                .allowedOriginPatterns(properties.cors().originArray())
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-RateLimit-Limit", "X-RateLimit-Remaining", "X-Quota-Remaining", "Retry-After")
                .maxAge(3600);
        // allowedOriginPatterns 允许与 allowCredentials 同时使用(不同于 allowedOrigins("*"))
        registration.allowCredentials(true);
    }
}
