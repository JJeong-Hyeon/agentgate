package com.agentgate.user.controller;

import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import com.agentgate.user.oidc.OidcLoginSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import com.agentgate.common.response.ErrorResponse;
import com.agentgate.user.dto.UserResponse;
import com.agentgate.user.service.LoginAttemptService;
import com.agentgate.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Console sign-in with a server session: the browser keeps only an {@code HttpOnly} session cookie, never the
 * password. API clients and scripts keep using HTTP Basic.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final LoginAttemptService loginAttempts;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();
    private final WebAuthenticationDetailsSource detailsSource = new WebAuthenticationDetailsSource();

    private final boolean localLoginEnabled;
    private final OidcLoginSupport oidc;

    public AuthController(AuthenticationManager authenticationManager, UserService userService,
                          LoginAttemptService loginAttempts, ObjectProvider<OidcLoginSupport> oidc,
                          @Value("${agentgate.auth.local-login-enabled:true}") boolean localLoginEnabled) {
        this.localLoginEnabled = localLoginEnabled;
        this.oidc = oidc.getIfAvailable();
        this.authenticationManager = authenticationManager;
        this.userService = userService;
        this.loginAttempts = loginAttempts;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    /** How the console may sign in; public, so the login page can show the right buttons. */
    public record AuthConfig(boolean localLoginEnabled, boolean ssoEnabled, String ssoLabel) {
    }

    @GetMapping("/config")
    public AuthConfig config() {
        return new AuthConfig(localLoginEnabled, oidc != null, oidc == null ? null : oidc.properties().buttonLabel());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest login, HttpServletRequest request,
                                   HttpServletResponse response) {
        if (!localLoginEnabled) {
            return error(HttpStatus.FORBIDDEN, "LOCAL_LOGIN_DISABLED", "Sign in with SSO", request, new HttpHeaders());
        }
        Optional<Duration> blocked = loginAttempts.blockedFor(login.username(), request.getRemoteAddr());
        if (blocked.isPresent()) {
            long seconds = Math.max(1, (blocked.get().toMillis() + 999) / 1000);
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
            return error(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_LOGIN_ATTEMPTS",
                    "Too many failed sign-in attempts; try again in %d seconds".formatted(seconds), request, headers);
        }
        UsernamePasswordAuthenticationToken token =
                UsernamePasswordAuthenticationToken.unauthenticated(login.username(), login.password());
        token.setDetails(detailsSource.buildDetails(request));
        Authentication authentication;
        try {
            // Failures and successes reach LoginAttemptService as authentication events, as with Basic.
            authentication = authenticationManager.authenticate(token);
        } catch (AuthenticationException e) {
            return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password", request,
                    new HttpHeaders());
        }
        // A new session id for the signed-in user (session fixation).
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return ResponseEntity.ok(userService.me(authentication.getName()));
    }

    /** For an SSO sign-in, where to send the browser next so the IdP's own session ends too. */
    public record LogoutResponse(String logoutUrl) {
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        String idpLogoutUrl = oidc == null ? null : oidc.endSessionUrl(
                SecurityContextHolder.getContext().getAuthentication(),
                ServletUriComponentsBuilder.fromContextPath(request).path("/").build().toUriString());
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return idpLogoutUrl == null
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(new LogoutResponse(idpLogoutUrl));
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
                                                       HttpServletRequest request, HttpHeaders headers) {
        // No WWW-Authenticate: the browser must not open its own sign-in dialog.
        return ResponseEntity.status(status).headers(headers).body(new ErrorResponse(Instant.now(), status.value(),
                status.getReasonPhrase(), code, message, request.getRequestURI()));
    }
}
