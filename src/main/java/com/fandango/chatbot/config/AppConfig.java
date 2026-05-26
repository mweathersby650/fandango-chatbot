package com.fandango.chatbot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableScheduling
public class AppConfig {

    @Bean
    public RestClient restClient() {
        return RestClient.builder()
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "FandangoAtHome-Chatbot/1.0")
                .build();
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/chat")
                        .allowedOriginPatterns("https://*.fandango.com", "http://localhost:*")
                        .allowedMethods("POST", "OPTIONS")
                        .allowedHeaders("*")
                        .maxAge(3600);
                registry.addMapping("/health")
                        .allowedOriginPatterns("*")
                        .allowedMethods("GET");
            }
        };
    }
}
