package com.agentgate.user.service;

import java.time.Duration;
import java.util.Optional;

/** Where {@link LoginAttemptService} keeps failure counts: in memory, or shared through Redis. */
interface LoginAttemptStore {

    /** How long {@code key} stays locked; empty when it is not. */
    Optional<Duration> lockRemaining(String key);

    /**
     * Counts a failure within the key's window and locks it for the lock duration once {@code limit} is reached.
     *
     * @return the failures counted in the current window
     */
    int fail(String key, int limit);

    void clear(String key);

    /** Drops entries that carry no information any more. */
    default void forgetExpired() {
    }
}
