package com.agentframework.messaging.redis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;

/**
 * Redis-backed CacheManager for distributed caching.
 * Active when cache.provider=redis, independent of the messaging provider.
 *
 * Cache regions:
 * - "prompts": system prompts (TTL 24h)
 * - "skills":  skill definitions (TTL 12h)
 */
@Configuration
@ConditionalOnProperty(name = "cache.provider", havingValue = "redis")
@EnableCaching
public class RedisCacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        var jsonSerializer = SerializationPair.fromSerializer(
                new GenericJackson2JsonRedisSerializer());

        var defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(24))
                .serializeValuesWith(jsonSerializer);

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withCacheConfiguration("prompts",
                        RedisCacheConfiguration.defaultCacheConfig()
                                .entryTtl(Duration.ofHours(24))
                                .serializeValuesWith(jsonSerializer))
                .withCacheConfiguration("skills",
                        RedisCacheConfiguration.defaultCacheConfig()
                                .entryTtl(Duration.ofHours(12))
                                .serializeValuesWith(jsonSerializer))
                .build();
    }
}
