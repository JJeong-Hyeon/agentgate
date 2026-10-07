package com.agentgate.config;

import com.agentgate.policy.domain.Policy;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String POLICIES = "policies";

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer(ObjectMapper objectMapper) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5));
        return builder -> builder
                .cacheDefaults(defaults.serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJacksonJsonRedisSerializer(objectMapper))))
                // The generic serializer stores no type info, so a cache hit would come back as
                // List<LinkedHashMap>. Pin the element type for caches holding entities.
                .withCacheConfiguration(POLICIES, defaults.serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                policiesSerializer(objectMapper))));
    }

    static RedisSerializer<List<Policy>> policiesSerializer(ObjectMapper objectMapper) {
        return new JacksonJsonRedisSerializer<>(objectMapper,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Policy.class));
    }
}
