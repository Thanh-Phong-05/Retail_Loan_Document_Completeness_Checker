package com.example.retail_loan_document_completeness_checker.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@Profile("dev")
public class DevCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        for (String path : new String[]{"/api/v1/dossiers", "/api/v1/dossiers/**"}) {
            registry.addMapping(path)
                    .allowedOrigins("http://localhost:5173", "http://localhost:3000")
                    .allowedMethods("GET", "POST")
                    .allowedHeaders("Authorization", "Content-Type", "X-XSRF-TOKEN");
        }
    }
}
