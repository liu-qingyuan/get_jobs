package com.getjobs.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import com.getjobs.application.workbench.WorkbenchLocalOnlyFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * CORS跨域配置
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter(@Value("${server.port:9527}") int backendPort) {
        CorsConfiguration config = new CorsConfiguration();

        // Personal profiles are local-only, including the Next.js development UI.
        config.setAllowedOrigins(WorkbenchLocalOnlyFilter.trustedOrigins(backendPort));

        // 允许所有请求头
        config.addAllowedHeader("*");

        // 允许所有请求方法
        config.addAllowedMethod("*");

        // 允许携带凭证
        config.setAllowCredentials(true);

        // 预检请求的有效期，单位为秒
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}
