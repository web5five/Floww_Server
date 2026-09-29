package com.floww.server.common.web;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/** Exact browser-origin allowlist; preflight must run before bearer authentication. */
@Configuration
public class FlowwCorsConfiguration {
    @Bean
    FilterRegistrationBean<CorsFilter> flowwCorsFilter(
            @Value("${floww.cors.allowed-origins:}") String allowedOrigins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(parseOrigins(allowedOrigins));
        cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    static List<String> parseOrigins(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        Set<String> origins = new LinkedHashSet<>();
        for (String item : Arrays.asList(raw.split(",", -1))) {
            String origin = item.trim();
            try {
                URI uri = URI.create(origin);
                boolean localHttp = "http".equals(uri.getScheme())
                        && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
                boolean https = "https".equals(uri.getScheme());
                if ((!https && !localHttp) || uri.getHost() == null || uri.getRawUserInfo() != null
                        || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || !origin.equals(uri.getScheme() + "://" + uri.getRawAuthority())) {
                    throw new IllegalArgumentException("Invalid CORS origin");
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("FLOWW_CORS_ALLOWED_ORIGINS must contain exact HTTPS origins "
                        + "(or localhost HTTP for local development)", invalid);
            }
            origins.add(origin);
        }
        return List.copyOf(origins);
    }
}
