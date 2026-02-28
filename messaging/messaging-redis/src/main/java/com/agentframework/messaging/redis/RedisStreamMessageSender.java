package com.agentframework.messaging.redis;

import com.agentframework.messaging.MessageEnvelope;
import com.agentframework.messaging.MessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis Streams implementation of MessageSender.
 * Uses XADD to append messages to a stream key (= destination).
 */
public class RedisStreamMessageSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamMessageSender.class);

    private final StringRedisTemplate redisTemplate;

    public RedisStreamMessageSender(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void send(MessageEnvelope envelope) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("body", envelope.body());

        if (envelope.messageId() != null) {
            fields.put("messageId", envelope.messageId());
        }

        // Copy envelope properties as stream record fields
        envelope.properties().forEach(fields::put);

        RecordId recordId = redisTemplate.opsForStream()
                .add(envelope.destination(), fields);

        log.debug("XADD to stream '{}', recordId={}, messageId={}",
                  envelope.destination(), recordId, envelope.messageId());
    }
}
