package com.agentgate.config;

import com.agentgate.user.service.LoginAttemptService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfig {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "TRACE", "OPTIONS");
    private static final String ADMIN = "ADMIN";
    private static final String EDITOR = "EDITOR";
    private static final String APPROVER = "APPROVER";
    private static final String[] ALL_ROLES = {"ADMIN", "EDITOR", "APPROVER", "VIEWER"};

    // The console loads only its own bundle and calls only this origin; inline styles come from React Flow.
    private static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; "
            + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    // Console APIs behind sign-in; specific rules above take precedence.
    private static final String[] CONSOLE_RESOURCES = {
            "/api/v1/dashboard",
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
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * CSRF only matters for requests the browser authenticates by itself, i.e. with a live session cookie. Header
     * credentials (Basic, API key, runtime token) are never added by a cross-site page, and requests without a
     * session carry nothing to abuse. Sign-in itself starts a session, so it cannot need one.
     */
    static boolean csrfExempt(HttpServletRequest request) {
        return request.getSession(false) == null
                || request.getHeader(HttpHeaders.AUTHORIZATION) != null
                || request.getHeader("X-API-Key") != null
                || request.getHeader("X-Runtime-Token") != null
                || "/api/v1/auth/login".equals(request.getRequestURI());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, LoginAttemptService loginAttempts,
                                                   ObjectMapper objectMapper) throws Exception {
        http
                // The console reads the XSRF-TOKEN cookie and sends it back in X-XSRF-TOKEN.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(request -> !SAFE_METHODS.contains(request.getMethod())
                                && !csrfExempt(request)))
                // A rejected request must not leave a session (and so a CSRF duty) behind.
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .httpBasic(Customizer.withDefaults())
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY)))
                // Repeated failed sign-ins are refused before their password is checked.
                .addFilterBefore(new LoginThrottleFilter(loginAttempts, objectMapper), BasicAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // Agents (API key) and the runtime (runtime token) authenticate in their controllers.
                        .requestMatchers("/api/v1/actions").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/executions/*/events").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/runtime/mcp-servers").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/runtime/memories", "/api/v1/runtime/memories/search")
                        .permitAll()
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
