package com.animeviewer.service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS：前端（localhost:5173 dev / 任意静态部署）跨域访问 /api/**。
 *  门禁由 Token 承担（服务默认仅绑定 127.0.0.1），故 CORS 放开来源即可。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Range", "Accept-Ranges", "X-AV-Remux")
                .maxAge(3600);
    }
}
