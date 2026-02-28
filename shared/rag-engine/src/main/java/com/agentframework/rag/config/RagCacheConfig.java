package com.agentframework.rag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Configures a dedicated Redis connection for RAG caching (DB 5).
 * Separate from the messaging Redis (DB 3) and app cache Redis (DB 4).
 */
@Configuration
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagCacheConfig {

    private static final Logger log = LoggerFactory.getLogger(RagCacheConfig.class);

    @Bean("ragRedisConnectionFactory")
    public LettuceConnectionFactory ragRedisConnectionFactory(RagProperties properties) {
        var cache = properties.cache();
        var redisHost = System.getenv().getOrDefault("REDIS_HOST", "redis");
        var redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

        log.info("[RAG] Configuring Redis cache on DB {} ({}:{})",
                cache.redisDb(), redisHost, redisPort);

        var config = new RedisStandaloneConfiguration(redisHost, redisPort);
        config.setDatabase(cache.redisDb());
        return new LettuceConnectionFactory(config);
    }

    @Bean("ragRedisTemplate")
    public StringRedisTemplate ragRedisTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("ragRedisConnectionFactory")
            LettuceConnectionFactory ragRedisConnectionFactory) {
        return new StringRedisTemplate(ragRedisConnectionFactory);
    }
}
