# Skill: Transactional Outbox Pattern

## When to Use

Use the outbox pattern whenever a service must: (1) persist data to the database, AND (2) publish a message to Service Bus. These two operations must be atomic.

## Database Schema

```sql
-- Flyway: V{N}__create_outbox_table.sql
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

```sql
-- Flyway: V{N+1}__create_processed_messages.sql
CREATE TABLE processed_messages (
    message_id   VARCHAR(255) PRIMARY KEY,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
```

## Producer Side: Write to Outbox

```java
@Service
@Transactional
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository orderRepo;
    private final OutboxRepository outboxRepo;
    private final ObjectMapper objectMapper;

    public Order createOrder(CreateOrderCommand cmd) {
        // 1. Business write
        var order = Order.from(cmd);
        orderRepo.save(order);

        // 2. Outbox write (SAME transaction)
        outboxRepo.save(OutboxEntry.builder()
            .aggregateType("Order")
            .aggregateId(order.getId().toString())
            .eventType("OrderCreated")
            .payload(objectMapper.valueToTree(order))
            .build());

        return order;
    }
}
```

## Outbox Poller: Publish to Service Bus

```java
@Component
@RequiredArgsConstructor
public class OutboxPoller {
    private final OutboxRepository outboxRepo;
    private final ServiceBusSenderClient sender;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void pollAndPublish() {
        var pending = outboxRepo.findTop100ByPublishedAtIsNullOrderByCreatedAt();
        for (var entry : pending) {
            var msg = new ServiceBusMessage(entry.getPayload().toString());
            msg.setMessageId(entry.getId().toString());
            msg.getApplicationProperties().put("eventType", entry.getEventType());
            msg.getApplicationProperties().put("aggregateType", entry.getAggregateType());
            try {
                sender.sendMessage(msg);
                entry.setPublishedAt(Instant.now());
            } catch (Exception e) {
                entry.setRetryCount(entry.getRetryCount() + 1);
                log.warn("Outbox publish failed id={} retry={}", entry.getId(), entry.getRetryCount(), e);
            }
        }
    }
}
```

## Consumer Side: Idempotent Processing

```java
@Component
@RequiredArgsConstructor
public class IdempotentProcessor {
    private final ProcessedMessageRepository processedRepo;

    @Transactional
    public boolean tryProcess(String messageId, Runnable handler) {
        if (processedRepo.existsById(messageId)) {
            log.info("Duplicate message {} skipped", messageId);
            return false;
        }
        handler.run();
        processedRepo.save(new ProcessedMessage(messageId, Instant.now()));
        return true;
    }
}
```

## Cleanup

Schedule periodic cleanup to prevent unbounded table growth:

```sql
-- Run daily: delete outbox entries published more than 7 days ago
DELETE FROM outbox WHERE published_at < NOW() - INTERVAL '7 days';

-- Run daily: delete processed messages older than 1 day
-- (Service Bus dedup window is ~10 minutes; 1 day is generous)
DELETE FROM processed_messages WHERE processed_at < NOW() - INTERVAL '1 day';
```

## Rules for Workers

1. NEVER call `sender.sendMessage()` directly from a `@Transactional` method. Always go through the outbox.
2. The outbox entry `id` (UUID) becomes the `messageId` on Service Bus. This is the idempotency key.
3. Every consumer MUST use `IdempotentProcessor.tryProcess()` before executing business logic.
4. The outbox poller batch size (100) and delay (1s) are tunable. Do not reduce the delay below 200ms without load testing.
5. If a message fails permanently (retry_count > 10), log an alert and skip it. Do not block the poller.
