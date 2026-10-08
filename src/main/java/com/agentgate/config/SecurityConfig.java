package com.agentgate.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/actions").permitAll()
                        .requestMatchers("/api/v1/policies", "/api/v1/policies/**").authenticated()
                        .requestMatchers("/api/v1/approvals", "/api/v1/approvals/**").authenticated()
                        .requestMatchers("/api/v1/audit-logs", "/api/v1/audit-logs/**").authenticated()
                        .requestMatchers("/api/v1/agents", "/api/v1/agents/**").authenticated()
                        .requestMatchers("/api/v1/workflows", "/api/v1/workflows/**").authenticated()
                        .requestMatchers("/api/v1/tools", "/api/v1/tools/**").authenticated()
                        .requestMatchers("/api/v1/tool-risks", "/api/v1/tool-risks/**").authenticated()
                        .requestMatchers("/api/v1/mcp-servers", "/api/v1/mcp-servers/**").authenticated()
                        // The runtime reads registered MCP servers with the shared runtime token instead.
                        .requestMatchers(HttpMethod.GET, "/api/v1/runtime/mcp-servers").permitAll()
                        // The runtime authenticates progress events with the shared runtime token instead.
                        .requestMatchers(HttpMethod.POST, "/api/v1/executions/*/events").permitAll()
                        .requestMatchers("/api/v1/executions", "/api/v1/executions/**").authenticated()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        // Bundled frontend: static files and client-side routes; the UI logs in against the API.
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/favicon.ico",
                                "/workflows", "/workflows/**", "/executions", "/executions/**", "/approvals",
                                "/agents", "/agents/**", "/tools").permitAll()
                        .anyRequest().denyAll()
                );
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder,
                                                  @Value("${agentgate.admin.username}") String adminUsername,
                                                  @Value("${agentgate.admin.password}") String adminPassword) {
        return new InMemoryUserDetailsManager(
                User.withUsername(adminUsername)
                        .password(passwordEncoder.encode(adminPassword))
                        .roles("ADMIN")
                        .build()
        );
    }
}
