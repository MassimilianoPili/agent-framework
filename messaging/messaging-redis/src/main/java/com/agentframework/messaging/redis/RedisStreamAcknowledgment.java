package com.agentframework.messaging.redis;

import com.agentframework.messaging.MessageAcknowledgment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

/**
 * Redis Streams acknowledgment.
 * complete() = XACK (removes from pending entries list).
 * reject() = XACK + XADD to dead-letter stream.
 */
public class RedisStreamAcknowledgment implements MessageAcknowledgment {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamAcknowledgment.class);

    private final StringRedisTemplate redisTemplate;
    private final String streamKey;
    private final String group;
    private final RecordId recordId;
    private final String originalBody;

    public RedisStreamAcknowledgment(StringRedisTemplate redisTemplate,
                                      String streamKey,
                                      String group,
                                      RecordId recordId,
                                      String originalBody) {
        this.redisTemplate = redisTemplate;
        this.streamKey = streamKey;
        this.group = group;
        this.recordId = recordId;
        this.originalBody = originalBody;
    }

    @Override
    public void complete() {
        redisTemplate.opsForStream().acknowledge(streamKey, group, recordId);
    }

    @Override
    public void reject(String reason) {
        // Acknowledge the original message (remove from PEL)
        redisTemplate.opsForStream().acknowledge(streamKey, group, recordId);

        // Write to dead-letter stream
        String dlqKey = streamKey + ":dlq";
        redisTemplate.opsForStream().add(dlqKey, Map.of(
                "body", originalBody,
                "reason", reason != null ? reason : "unknown",
                "originalStream", streamKey,
                "originalId", recordId.getValue()
        ));

        log.warn("Message {} dead-lettered to '{}': {}", recordId, dlqKey, reason);
    }
}
