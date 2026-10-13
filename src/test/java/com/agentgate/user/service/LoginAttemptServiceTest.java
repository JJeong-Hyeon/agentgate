package com.agentgate.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginAttemptServiceTest {

    private static final String IP = "10.0.0.1";

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptService attempts = new LoginAttemptService(3, 5, Duration.ofMinutes(15), clock);

    @Test
    void reachingTheLimitBlocksThatUserFromThatAddressForTheLockDuration() {
        fail("alice", IP, 2);
        assertThat(attempts.blockedFor("alice", IP)).isEmpty();

        fail("alice", IP, 1);

        assertThat(attempts.blockedFor("alice", IP)).contains(Duration.ofMinutes(15));
        assertThat(attempts.blockedFor("alice", "10.0.0.2")).isEmpty();
        assertThat(attempts.blockedFor("bob", IP)).isEmpty();
        clock.advance(Duration.ofMinutes(10));
        assertThat(attempts.blockedFor("alice", IP)).contains(Duration.ofMinutes(5));
        clock.advance(Duration.ofMinutes(5));
        assertThat(attempts.blockedFor("alice", IP)).isEmpty();
    }

    @Test
    void aSuccessfulSignInStartsTheCountOver() {
        fail("alice", IP, 2);
        attempts.recordSuccess("alice", IP);
        fail("alice", IP, 2);

        assertThat(attempts.blockedFor("alice", IP)).isEmpty();
    }

    @Test
    void failuresOutsideTheWindowDoNotAddUp() {
        fail("alice", IP, 2);
        clock.advance(Duration.ofMinutes(16));
        fail("alice", IP, 2);

        assertThat(attempts.blockedFor("alice", IP)).isEmpty();
    }

    @Test
    void anAddressFailingAcrossUsernamesIsBlockedForAll() {
        for (String user : new String[] {"a", "b", "c", "d", "e"}) {
            fail(user, IP, 1);
        }

        assertThat(attempts.blockedFor("anyone", IP)).contains(Duration.ofMinutes(15));
        assertThat(attempts.blockedFor("anyone", "10.0.0.2")).isEmpty();
    }

    @Test
    void expiredEntriesAreForgotten() {
        fail("alice", IP, 3);
        attempts.forgetExpired();
        assertThat(attempts.blockedFor("alice", IP)).isPresent();

        clock.advance(Duration.ofMinutes(15));
        attempts.forgetExpired();
        fail("alice", IP, 2);

        assertThat(attempts.blockedFor("alice", IP)).isEmpty();
    }

    private void fail(String username, String ip, int times) {
        for (int i = 0; i < times; i++) {
            attempts.recordFailure(username, ip);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-13T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
