package com.xammer.cloud.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web configuration for the application.
 * Configures CORS to allow requests from the frontend.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // Apply to all endpoints
                // ✅ USE allowedOriginPatterns INSTEAD of allowedOrigins
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                // ✅ Specify headers explicitly instead of using wildcard
                .allowedHeaders(
                    "Authorization",
                    "Content-Type",
                    "Accept",
                    "X-Requested-With",
                    "Cache-Control",
                    "Origin",
                    "Access-Control-Request-Method",
                    "Access-Control-Request-Headers"
                )
                .exposedHeaders(
                    "Authorization",
                    "Content-Disposition",
                    "Access-Control-Allow-Origin",
                    "Access-Control-Allow-Credentials"
                )
                .allowCredentials(true)
                .maxAge(3600); // 1 hour max age for preflight requests
    }
}