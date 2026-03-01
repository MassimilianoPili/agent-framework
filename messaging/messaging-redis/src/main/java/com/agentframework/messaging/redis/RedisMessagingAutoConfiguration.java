package com.agentframework.messaging.redis;

import com.agentframework.messaging.MessageListenerContainer;
import com.agentframework.messaging.MessageSender;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Auto-configuration for Redis Streams messaging provider.
 * Active when messaging.provider=redis.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "messaging.provider", havingValue = "redis")
@ConditionalOnClass(RedisConnectionFactory.class)
@EnableConfigurationProperties(RedisMessagingProperties.class)
public class RedisMessagingAutoConfiguration {

    @Bean
    @Primary
    public RedisConnectionFactory redisMessagingConnectionFactory(RedisMessagingProperties properties) {
        var config = new RedisStandaloneConfiguration(properties.getHost(), properties.getPort());
        config.setDatabase(properties.getDatabase());
        return new LettuceConnectionFactory(config);
    }

    @Bean
    public StringRedisTemplate redisMessagingTemplate(RedisConnectionFactory redisMessagingConnectionFactory) {
        return new StringRedisTemplate(redisMessagingConnectionFactory);
    }

    @Bean
    public MessageSender messageSender(StringRedisTemplate redisMessagingTemplate) {
        return new RedisStreamMessageSender(redisMessagingTemplate);
    }

    @Bean
    public MessageListenerContainer messageListenerContainer(
            StringRedisTemplate redisMessagingTemplate,
            RedisMessagingProperties properties) {
        return new RedisStreamListenerContainer(redisMessagingTemplate, properties);
    }
}
