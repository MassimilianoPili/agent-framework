package com.agentframework.messaging.redis;

import com.agentframework.messaging.MessageHandler;
import com.agentframework.messaging.MessageListenerContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis Streams implementation of MessageListenerContainer.
 * Uses Spring Data Redis StreamMessageListenerContainer for non-blocking consumption.
 * Each subscribe() call creates a consumer group subscription with auto-ack disabled.
 */
public class RedisStreamListenerContainer implements MessageListenerContainer {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamListenerContainer.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisMessagingProperties properties;
    private final List<SubscriptionInfo> pendingSubscriptions = new ArrayList<>();
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private final List<Subscription> activeSubscriptions = new ArrayList<>();

    public RedisStreamListenerContainer(StringRedisTemplate redisTemplate,
                                         RedisMessagingProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public void subscribe(String destination, String group, MessageHandler handler) {
        log.info("Registering Redis Stream subscription: stream='{}', group='{}'", destination, group);
        pendingSubscriptions.add(new SubscriptionInfo(destination, group, handler));
    }

    @Override
    public void start() {
        if (pendingSubscriptions.isEmpty()) {
            log.warn("No Redis Stream subscriptions registered, nothing to start");
            return;
        }

        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .<String, MapRecord<String, String, String>>builder()
                .pollTimeout(Duration.ofMillis(properties.getPollTimeoutMs()))
                .batchSize(properties.getBatchSize())
                .build();

        container = StreamMessageListenerContainer.create(
                redisTemplate.getConnectionFactory(), options);

        String consumerId = getConsumerId();

        for (var info : pendingSubscriptions) {
            // Ensure the consumer group exists (XGROUP CREATE, MKSTREAM)
            ensureConsumerGroup(info.destination, info.group);

            var subscription = container.receive(
                    Consumer.from(info.group, consumerId),
                    StreamOffset.create(info.destination, ReadOffset.lastConsumed()),
                    message -> {
                        String body = message.getValue().get("body");
                        if (body == null) {
                            log.warn("Received Redis Stream message without 'body' field on '{}'",
                                     info.destination);
                            return;
                        }
                        var ack = new RedisStreamAcknowledgment(
                                redisTemplate, info.destination, info.group,
                                message.getId(), body);
                        info.handler.handle(body, ack);
                    }
            );
            activeSubscriptions.add(subscription);
            log.info("Subscribed to Redis Stream '{}' as consumer '{}' in group '{}'",
                     info.destination, consumerId, info.group);
        }

        // Reclaim pending messages from previous consumers that may have crashed.
        // This must happen before container.start() so reclaimed messages are processed
        // by the listener registered above, ensuring at-least-once delivery.
        for (var info : pendingSubscriptions) {
            reclaimPendingMessages(info.destination, info.group, consumerId, info.handler);
        }

        container.start();
        log.info("Redis Stream listener container started with {} subscription(s)",
                 activeSubscriptions.size());
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
            log.info("Redis Stream listener container stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return container != null && container.isRunning();
    }

    private void ensureConsumerGroup(String streamKey, String group) {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), group);
            log.info("Created consumer group '{}' on stream '{}'", group, streamKey);
        } catch (Exception e) {
            // Group already exists — this is expected on restart
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group '{}' already exists on stream '{}'", group, streamKey);
            } else {
                log.warn("Error creating consumer group '{}' on '{}': {}", group, streamKey, e.getMessage());
            }
        }
    }

    /**
     * Reclaims and reprocesses messages from the PEL (Pending Entries List) that have been
     * idle for longer than the configured idle timeout. Uses XPENDING + XACK pattern:
     * reads pending messages, dispatches them to the handler, lets the handler ACK them.
     */
    private void reclaimPendingMessages(String streamKey, String group, String consumerId,
                                         MessageHandler handler) {
        try {
            long idleMs = properties.getReclaimIdleMs();
            PendingMessages pending = redisTemplate.opsForStream()
                    .pending(streamKey, group, org.springframework.data.domain.Range.unbounded(), 100L);

            if (pending == null || pending.isEmpty()) {
                return;
            }

            int reclaimed = 0;
            for (var pm : pending) {
                if (pm.getElapsedTimeSinceLastDelivery().toMillis() < idleMs) {
                    continue;
                }

                // Read the actual message content by its ID
                var messages = redisTemplate.opsForStream()
                        .range(streamKey, org.springframework.data.domain.Range.closed(
                                pm.getIdAsString(), pm.getIdAsString()));

                if (messages != null && !messages.isEmpty()) {
                    var msg = messages.get(0);
                    Object bodyObj = msg.getValue().get("body");
                    if (bodyObj != null) {
                        String body = bodyObj.toString();
                        var ack = new RedisStreamAcknowledgment(
                                redisTemplate, streamKey, group, msg.getId(), body);
                        handler.handle(body, ack);
                        reclaimed++;
                    }
                }
            }

            if (reclaimed > 0) {
                log.info("Reclaimed {} pending message(s) from stream '{}' group '{}'",
                         reclaimed, streamKey, group);
            }
        } catch (Exception e) {
            log.warn("Failed to reclaim pending messages from '{}': {}", streamKey, e.getMessage());
        }
    }

    private String getConsumerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
        } catch (Exception e) {
            return "consumer-" + ProcessHandle.current().pid();
        }
    }

    private record SubscriptionInfo(String destination, String group, MessageHandler handler) {}
}
