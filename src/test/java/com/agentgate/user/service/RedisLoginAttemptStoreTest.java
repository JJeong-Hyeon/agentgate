package com.agentgate.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class RedisLoginAttemptStoreTest {

    private static GenericContainer<?> container;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void startRedis() {
        container = new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);
        container.start();
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        factory.destroy();
        container.stop();
    }

    @Test
    void failuresAreSharedAndLockAtTheLimitUntilTheyExpire() throws Exception {
        RedisLoginAttemptStore first = new RedisLoginAttemptStore(redis, Duration.ofSeconds(2));
        // Another instance (or the same one after a restart) sees the same counts.
        RedisLoginAttemptStore second = new RedisLoginAttemptStore(redis, Duration.ofSeconds(2));

        assertThat(first.fail("user:a", 3)).isEqualTo(1);
        assertThat(second.fail("user:a", 3)).isEqualTo(2);
        assertThat(first.lockRemaining("user:a")).isEmpty();
        assertThat(second.fail("user:a", 3)).isEqualTo(3);

        assertThat(first.lockRemaining("user:a")).isPresent();
        assertThat(first.lockRemaining("user:b")).isEmpty();
        Thread.sleep(2200);
        assertThat(second.lockRemaining("user:a")).isEmpty();
        assertThat(second.fail("user:a", 3)).isEqualTo(1);
    }

    @Test
    void clearingForgetsTheCountAndTheLock() {
        RedisLoginAttemptStore store = new RedisLoginAttemptStore(redis, Duration.ofMinutes(1));
        store.fail("user:c", 1);
        assertThat(store.lockRemaining("user:c")).isPresent();

        store.clear("user:c");

        assertThat(store.lockRemaining("user:c")).isEmpty();
        assertThat(store.fail("user:c", 5)).isEqualTo(1);
    }

    @Test
    void anUnreachableRedisBlocksNobody() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.getExpire("agentgate:login:lock:k", java.util.concurrent.TimeUnit.MILLISECONDS))
                .thenThrow(new RedisConnectionFailureException("down"));
        when(broken.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        RedisLoginAttemptStore store = new RedisLoginAttemptStore(broken, Duration.ofMinutes(1));

        assertThat(store.lockRemaining("k")).isEmpty();
        assertThat(store.fail("k", 1)).isZero();
    }
}
