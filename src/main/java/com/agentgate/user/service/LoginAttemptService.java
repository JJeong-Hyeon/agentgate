package com.agentgate.user.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
 * failing across many usernames is blocked as a whole. Counts live in memory: they reset on restart and
 * are per instance.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private final int maxFailures;
    private final int maxFailuresPerIp;
    private final Duration lockDuration;
    private final Clock clock;
    // Key: "<ip> <username>" for one user from one address, "<ip>" for the address across users.
    private final Map<String, Attempts> byUser = new ConcurrentHashMap<>();
    private final Map<String, Attempts> byIp = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptService(@Value("${agentgate.login.max-failures:5}") int maxFailures,
                               @Value("${agentgate.login.max-failures-per-ip:20}") int maxFailuresPerIp,
                               @Value("${agentgate.login.lock-duration:PT15M}") Duration lockDuration) {
        this(maxFailures, maxFailuresPerIp, lockDuration, Clock.systemUTC());
    }

    LoginAttemptService(int maxFailures, int maxFailuresPerIp, Duration lockDuration, Clock clock) {
        this.maxFailures = maxFailures;
        this.maxFailuresPerIp = maxFailuresPerIp;
        this.lockDuration = lockDuration;
        this.clock = clock;
    }

    /** How long sign-in stays blocked for this username from this address; empty when it may try. */
    public Optional<Duration> blockedFor(String username, String ip) {
        Instant now = clock.instant();
        return later(remaining(byIp.get(ip), now), remaining(byUser.get(userKey(username, ip)), now));
    }

    public void recordFailure(String username, String ip) {
        Instant now = clock.instant();
        Attempts user = byUser.compute(userKey(username, ip), (k, a) -> fail(a, now, maxFailures));
        Attempts address = byIp.compute(ip, (k, a) -> fail(a, now, maxFailuresPerIp));
        if (user.lockedFrom(now, maxFailures)) {
            log.warn("Sign-in for '{}' from {} blocked for {} after {} failures", username, ip, lockDuration,
                    user.failures());
        }
        if (address.lockedFrom(now, maxFailuresPerIp)) {
            log.warn("Sign-in from {} blocked for {} after {} failures", ip, lockDuration, address.failures());
        }
    }

    public void recordSuccess(String username, String ip) {
        byUser.remove(userKey(username, ip));
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
        Instant now = clock.instant();
        byUser.values().removeIf(a -> a.expired(now, lockDuration));
        byIp.values().removeIf(a -> a.expired(now, lockDuration));
    }

    // Failures count within one lock-duration window from the first; reaching the limit locks for that long.
    private Attempts fail(Attempts current, Instant now, int limit) {
        boolean fresh = current == null || current.expired(now, lockDuration);
        int failures = fresh ? 1 : current.failures() + 1;
        Instant windowStart = fresh ? now : current.windowStart();
        Instant lockedUntil = fresh ? null : current.lockedUntil();
        if (failures >= limit && (lockedUntil == null || !lockedUntil.isAfter(now))) {
            lockedUntil = now.plus(lockDuration);
        }
        return new Attempts(failures, windowStart, lockedUntil);
    }

    private static Optional<Duration> remaining(Attempts attempts, Instant now) {
        if (attempts == null || attempts.lockedUntil() == null || !attempts.lockedUntil().isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, attempts.lockedUntil()));
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
        return ip + " " + username;
    }

    private static String clientIp(Authentication authentication) {
        return authentication.getDetails() instanceof WebAuthenticationDetails details
                ? details.getRemoteAddress() : null;
    }

    private record Attempts(int failures, Instant windowStart, Instant lockedUntil) {

        boolean lockedFrom(Instant now, int limit) {
            return failures == limit && lockedUntil != null && lockedUntil.isAfter(now);
        }

        boolean expired(Instant now, Duration window) {
            boolean windowOver = !windowStart.plus(window).isAfter(now);
            boolean lockOver = lockedUntil == null || !lockedUntil.isAfter(now);
            return windowOver && lockOver;
        }
    }
}
