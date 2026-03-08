# Transactional Outbox and Idempotency

## Problem
When a service writes to the database AND sends a message to Service Bus, these are two separate operations. If the app crashes between the DB commit and the message send, the system is inconsistent: the DB has the data but downstream consumers never learn about it. Conversely, if a message is delivered more than once (at-least-once delivery), the consumer may process it twice.

## Solution
1. **Transactional Outbox**: Write the message to an `outbox` table in the SAME database transaction as the business data. A separate poller/CDC process reads the outbox and publishes to Service Bus.
2. **Idempotency Key**: Use the Service Bus `messageId` as an idempotency key. Consumers track processed message IDs and skip duplicates.

### Outbox Table (Flyway Migration)

```sql
-- V2__create_outbox_table.sql
CREATE TABLE outbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(255) NOT NULL,
    aggregate_id    VARCHAR(255) NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMPTZ,
    retry_count     INT          NOT NULL DEFAULT 0
);

CREATE INDEX idx_outbox_unpublished ON outbox (created_at)
    WHERE published_at IS NULL;
```

### Writing to Outbox (Same Transaction)

```java
@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository orderRepo;
    private final OutboxRepository outboxRepo;

    @Transactional
    public Order createOrder(CreateOrderCommand cmd) {
        var order = Order.from(cmd);
        orderRepo.save(order);

        var outboxEntry = OutboxEntry.builder()
            .aggregateType("Order")
            .aggregateId(order.getId().toString())
            .eventType("OrderCreated")
            .payload(objectMapper.valueToTree(order))
            .build();
        outboxRepo.save(outboxEntry);

        return order;
    }
}
```

### Outbox Poller

```java
@Component
@RequiredArgsConstructor
public class OutboxPoller {
    private final OutboxRepository outboxRepo;
    private final ServiceBusSenderClient sender;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void pollAndPublish() {
        var entries = outboxRepo.findTop100ByPublishedAtIsNullOrderByCreatedAt();
        for (var entry : entries) {
            var msg = new ServiceBusMessage(entry.getPayload().toString());
            msg.setMessageId(entry.getId().toString());  // idempotency key
            msg.getApplicationProperties().put("eventType", entry.getEventType());
            try {
                sender.sendMessage(msg);
                entry.setPublishedAt(Instant.now());
            } catch (Exception e) {
                entry.setRetryCount(entry.getRetryCount() + 1);
                log.warn("Outbox publish failed for {}, retry {}", entry.getId(), entry.getRetryCount());
            }
        }
    }
}
```

### Consumer-Side Idempotency

```sql
-- V3__create_processed_messages.sql
CREATE TABLE processed_messages (
    message_id  VARCHAR(255) PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

```java
@Component
@RequiredArgsConstructor
public class IdempotentMessageHandler {
    private final ProcessedMessageRepository processedRepo;

    @Transactional
    public boolean tryProcess(String messageId, Runnable handler) {
        if (processedRepo.existsById(messageId)) {
            log.info("Duplicate message {}, skipping", messageId);
            return false;
        }
        handler.run();
        processedRepo.save(new ProcessedMessage(messageId, Instant.now()));
        return true;
    }
}
```

### Deduplication Strategy

| Layer | Mechanism | Scope |
|-------|-----------|-------|
| Service Bus | `messageId` dedup (if session-enabled) | ~10 min window |
| Application | `processed_messages` table | Permanent |
| Outbox | UUID as `messageId` ensures stable identity | Source |

## Trade-offs

- **Pro**: Guarantees exactly-once semantics from the producer's perspective; at-least-once + idempotency gives effectively-once on the consumer.
- **Pro**: No distributed transactions (2PC) needed; works with any SQL database.
- **Con**: Outbox poller adds latency (configurable, default 1 second). For sub-second needs, consider CDC (Debezium).
- **Con**: `processed_messages` table grows indefinitely. Schedule periodic cleanup of entries older than the Service Bus message lock duration (default 5 minutes, add safety margin).
