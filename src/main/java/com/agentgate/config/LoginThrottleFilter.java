package com.agentgate.config;

import com.agentgate.common.response.ErrorResponse;
import com.agentgate.user.service.LoginAttemptService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Answers 429 to HTTP Basic sign-ins that {@link LoginAttemptService} has blocked, before the password is
 * checked, so a blocked client learns nothing from further guesses.
 */
public class LoginThrottleFilter extends OncePerRequestFilter {

    private static final String BASIC = "basic ";

    private final LoginAttemptService loginAttempts;
    private final ObjectMapper objectMapper;

    public LoginThrottleFilter(LoginAttemptService loginAttempts, ObjectMapper objectMapper) {
        this.loginAttempts = loginAttempts;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> username = basicUsername(request.getHeader(HttpHeaders.AUTHORIZATION));
        Optional<Duration> blocked = username.flatMap(u -> loginAttempts.blockedFor(u, request.getRemoteAddr()));
        if (blocked.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        long seconds = Math.max(1, (blocked.get().toMillis() + 999) / 1000);
        HttpStatus status = HttpStatus.TOO_MANY_REQUESTS;
        response.setStatus(status.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(Instant.now(), status.value(),
                status.getReasonPhrase(), "TOO_MANY_LOGIN_ATTEMPTS",
                "Too many failed sign-in attempts; try again in %d seconds".formatted(seconds),
                request.getRequestURI()));
    }

    // Malformed headers pass through; Basic authentication rejects them itself.
    static Optional<String> basicUsername(String header) {
        if (header == null || !header.regionMatches(true, 0, BASIC, 0, BASIC.length())) {
            return Optional.empty();
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(BASIC.length()).trim()),
                    StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            return colon < 0 ? Optional.empty() : Optional.of(decoded.substring(0, colon));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
