package com.agentgate.user.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Counts in this instance's memory: reset by a restart and not shared between instances. */
@Component
@ConditionalOnProperty(name = "agentgate.login.store", havingValue = "memory", matchIfMissing = true)
class MemoryLoginAttemptStore implements LoginAttemptStore {

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final Duration lockDuration;
    private final Clock clock;

    @Autowired
    MemoryLoginAttemptStore(@Value("${agentgate.login.lock-duration:PT15M}") Duration lockDuration) {
        this(lockDuration, Clock.systemUTC());
    }

    MemoryLoginAttemptStore(Duration lockDuration, Clock clock) {
        this.lockDuration = lockDuration;
        this.clock = clock;
    }

    @Override
    public Optional<Duration> lockRemaining(String key) {
        Attempts current = attempts.get(key);
        Instant now = clock.instant();
        if (current == null || current.lockedUntil() == null || !current.lockedUntil().isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, current.lockedUntil()));
    }

    // Failures count within one lock-duration window from the first; reaching the limit locks for that long.
    @Override
    public int fail(String key, int limit) {
        Instant now = clock.instant();
        return attempts.compute(key, (k, current) -> {
            boolean fresh = current == null || current.expired(now, lockDuration);
            int failures = fresh ? 1 : current.failures() + 1;
            Instant windowStart = fresh ? now : current.windowStart();
            Instant lockedUntil = fresh ? null : current.lockedUntil();
            if (failures >= limit && (lockedUntil == null || !lockedUntil.isAfter(now))) {
                lockedUntil = now.plus(lockDuration);
            }
            return new Attempts(failures, windowStart, lockedUntil);
        }).failures();
    }

    @Override
    public void clear(String key) {
        attempts.remove(key);
    }

    @Override
    public void forgetExpired() {
        Instant now = clock.instant();
        attempts.values().removeIf(a -> a.expired(now, lockDuration));
    }

    private record Attempts(int failures, Instant windowStart, Instant lockedUntil) {

        boolean expired(Instant now, Duration window) {
            boolean windowOver = !windowStart.plus(window).isAfter(now);
            boolean lockOver = lockedUntil == null || !lockedUntil.isAfter(now);
            return windowOver && lockOver;
        }
    }
}
