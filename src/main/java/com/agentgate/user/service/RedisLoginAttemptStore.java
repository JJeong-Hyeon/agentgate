package com.agentgate.user.service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Counts in Redis, so a restart does not forget them and every instance sees the same counts. If Redis cannot be
 * reached nobody is blocked: a Redis outage must not lock everyone out of the console.
 */
@Component
@ConditionalOnProperty(name = "agentgate.login.store", havingValue = "redis")
class RedisLoginAttemptStore implements LoginAttemptStore {

    private static final Logger log = LoggerFactory.getLogger(RedisLoginAttemptStore.class);
    private static final String PREFIX = "agentgate:login:";

    private final StringRedisTemplate redis;
    private final Duration lockDuration;

    @Autowired
    RedisLoginAttemptStore(StringRedisTemplate redis,
                           @Value("${agentgate.login.lock-duration:PT15M}") Duration lockDuration) {
        this.redis = redis;
        this.lockDuration = lockDuration;
    }

    @Override
    public Optional<Duration> lockRemaining(String key) {
        try {
            Long millis = redis.getExpire(lock(key), TimeUnit.MILLISECONDS);
            return millis != null && millis > 0 ? Optional.of(Duration.ofMillis(millis)) : Optional.empty();
        } catch (DataAccessException e) {
            log.warn("Sign-in throttle unavailable (Redis); not blocking", e);
            return Optional.empty();
        }
    }

    @Override
    public int fail(String key, int limit) {
        try {
            Long failures = redis.opsForValue().increment(count(key));
            if (failures == null) {
                return 0;
            }
            if (failures == 1) {
                redis.expire(count(key), lockDuration);
            }
            if (failures >= limit) {
                redis.opsForValue().setIfAbsent(lock(key), "1", lockDuration);
            }
            return failures.intValue();
        } catch (DataAccessException e) {
            log.warn("Sign-in failure not counted (Redis unavailable)", e);
            return 0;
        }
    }

    @Override
    public void clear(String key) {
        try {
            redis.delete(java.util.List.of(count(key), lock(key)));
        } catch (DataAccessException e) {
            log.warn("Sign-in counts not cleared (Redis unavailable)", e);
        }
    }

    private static String count(String key) {
        return PREFIX + "count:" + key;
    }

    private static String lock(String key) {
        return PREFIX + "lock:" + key;
    }
}
