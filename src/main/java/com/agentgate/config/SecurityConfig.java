package com.agentgate.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    private static final String ADMIN = "ADMIN";
    private static final String EDITOR = "EDITOR";
    private static final String APPROVER = "APPROVER";
    private static final String[] ALL_ROLES = {"ADMIN", "EDITOR", "APPROVER", "VIEWER"};

    // Console APIs behind sign-in; specific rules above take precedence.
    private static final String[] CONSOLE_RESOURCES = {
            "/api/v1/agents", "/api/v1/agents/**",
            "/api/v1/policies", "/api/v1/policies/**",
            "/api/v1/approvals", "/api/v1/approvals/**",
            "/api/v1/audit-logs", "/api/v1/audit-logs/**",
            "/api/v1/workflows", "/api/v1/workflows/**",
            "/api/v1/executions", "/api/v1/executions/**",
            "/api/v1/tools", "/api/v1/tools/**",
            "/api/v1/tool-risks", "/api/v1/tool-risks/**",
            "/api/v1/mcp-servers", "/api/v1/mcp-servers/**"};

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        // Agents (API key) and the runtime (runtime token) authenticate in their controllers.
                        .requestMatchers("/api/v1/actions").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/executions/*/events").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/runtime/mcp-servers").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()

                        // Every signed-in user: who am I, and my own password.
                        .requestMatchers("/api/v1/me", "/api/v1/me/**").authenticated()

                        // ADMIN: users and system settings.
                        .requestMatchers("/api/v1/users", "/api/v1/users/**").hasRole(ADMIN)
                        // EDITOR: agent definitions, workflows, executions.
                        .requestMatchers(HttpMethod.PUT, "/api/v1/agents/*/definition").hasAnyRole(ADMIN, EDITOR)
                        .requestMatchers(HttpMethod.POST, "/api/v1/workflows", "/api/v1/workflows/**").hasAnyRole(ADMIN, EDITOR)
                        .requestMatchers(HttpMethod.POST, "/api/v1/executions").hasAnyRole(ADMIN, EDITOR)
                        // APPROVER: decisions.
                        .requestMatchers(HttpMethod.POST, "/api/v1/approvals/*/approve", "/api/v1/approvals/*/reject")
                        .hasAnyRole(ADMIN, APPROVER)

                        // Reading the console's resources: any role.
                        .requestMatchers(HttpMethod.GET, CONSOLE_RESOURCES).hasAnyRole(ALL_ROLES)
                        // Changing anything else there (agents, keys, policies, tool risks, MCP servers): ADMIN.
                        .requestMatchers(CONSOLE_RESOURCES).hasRole(ADMIN)

                        // Bundled frontend: static files and client-side routes; the UI logs in against the API.
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/favicon.ico",
                                "/workflows", "/workflows/**", "/executions", "/executions/**", "/approvals",
                                "/agents", "/agents/**", "/tools", "/users", "/account").permitAll()
                        .anyRequest().denyAll()
                );
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
