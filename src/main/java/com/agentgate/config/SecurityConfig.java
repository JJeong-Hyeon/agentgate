package com.agentgate.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/actions").permitAll()
                        .requestMatchers("/api/v1/policies", "/api/v1/policies/**").permitAll()
                        .requestMatchers("/api/v1/approvals", "/api/v1/approvals/**").permitAll()
                        .requestMatchers("/api/v1/audit-logs", "/api/v1/audit-logs/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().denyAll()
                );
        return http.build();
    }
}
