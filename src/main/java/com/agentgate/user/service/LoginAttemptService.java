package com.agentgate.user.service;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Service;

/**
 * Slows down password guessing on the console sign-in. Failures are counted per username and client IP,
 * so an attacker locks out only their own address, not the user signing in from elsewhere; a client IP
 * failing across many usernames is blocked as a whole. Counts are kept by a {@link LoginAttemptStore}:
 * in memory (reset by a restart, per instance) or in Redis (shared).
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private final int maxFailures;
    private final int maxFailuresPerIp;
    private final Duration lockDuration;
    private final LoginAttemptStore store;

    @Autowired
    LoginAttemptService(@Value("${agentgate.login.max-failures:5}") int maxFailures,
                        @Value("${agentgate.login.max-failures-per-ip:20}") int maxFailuresPerIp,
                        @Value("${agentgate.login.lock-duration:PT15M}") Duration lockDuration,
                        LoginAttemptStore store) {
        this.maxFailures = maxFailures;
        this.maxFailuresPerIp = maxFailuresPerIp;
        this.lockDuration = lockDuration;
        this.store = store;
    }

    LoginAttemptService(int maxFailures, int maxFailuresPerIp, Duration lockDuration, Clock clock) {
        this(maxFailures, maxFailuresPerIp, lockDuration, new MemoryLoginAttemptStore(lockDuration, clock));
    }

    /** How long sign-in stays blocked for this username from this address; empty when it may try. */
    public Optional<Duration> blockedFor(String username, String ip) {
        return later(store.lockRemaining(ipKey(ip)), store.lockRemaining(userKey(username, ip)));
    }

    public void recordFailure(String username, String ip) {
        int user = store.fail(userKey(username, ip), maxFailures);
        int address = store.fail(ipKey(ip), maxFailuresPerIp);
        if (user == maxFailures) {
            log.warn("Sign-in for '{}' from {} blocked for {} after {} failures", username, ip, lockDuration, user);
        }
        if (address == maxFailuresPerIp) {
            log.warn("Sign-in from {} blocked for {} after {} failures", ip, lockDuration, address);
        }
    }

    public void recordSuccess(String username, String ip) {
        store.clear(userKey(username, ip));
    }

    @EventListener
    public void onFailure(AuthenticationFailureBadCredentialsEvent event) {
        String ip = clientIp(event.getAuthentication());
        if (ip != null) {
            recordFailure(event.getAuthentication().getName(), ip);
        }
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        String ip = clientIp(event.getAuthentication());
        if (ip != null) {
            recordSuccess(event.getAuthentication().getName(), ip);
        }
    }

    // Entries whose window and lock are over carry no information; drop them so memory stays bounded.
    @Scheduled(fixedDelayString = "${agentgate.login.cleanup-delay:PT5M}")
    public void forgetExpired() {
        store.forgetExpired();
    }

    private static Optional<Duration> later(Optional<Duration> a, Optional<Duration> b) {
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        return a.get().compareTo(b.get()) >= 0 ? a : b;
    }

    private static String userKey(String username, String ip) {
        return "user:" + ip + " " + username;
    }

    private static String ipKey(String ip) {
        return "ip:" + ip;
    }

    private static String clientIp(Authentication authentication) {
        return authentication.getDetails() instanceof WebAuthenticationDetails details
                ? details.getRemoteAddress() : null;
    }
}
