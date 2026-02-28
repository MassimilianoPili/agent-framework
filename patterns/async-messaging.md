# Async Messaging with Azure Service Bus

## Problem
Synchronous HTTP calls between the control plane and workers create tight coupling, cascade failures, and head-of-line blocking. When an agent worker is busy or down, the orchestrator blocks.

## Solution
Use **Azure Service Bus Topics and Subscriptions** for async, durable communication. The control plane publishes task messages to a topic. Each worker type has its own subscription with server-side filtering. Failed messages land in a **Dead-Letter Queue (DLQ)** for inspection and replay.

### Topology

```
                     ┌─────────────────────────┐
                     │    tasks (topic)         │
                     └─────┬───────┬───────┬────┘
                           │       │       │
                 ┌─────────┘       │       └──────────┐
                 ▼                 ▼                   ▼
          ┌──────────┐     ┌──────────┐        ┌──────────┐
          │ be-worker│     │ fe-worker│        │ contract  │
          │ (sub)    │     │ (sub)    │        │ (sub)     │
          └──────────┘     └──────────┘        └──────────┘
```

### Message Format

```json
{
  "planId": "PLAN-42",
  "taskKey": "be-user-service",
  "workerType": "be",
  "payload": {
    "instruction": "Create UserService with CRUD operations",
    "contextFiles": ["contracts/user-api.yaml"],
    "skillFiles": ["skills/springboot-workflow-skills/03-service-layer.md"]
  },
  "metadata": {
    "correlationId": "PLAN-42",
    "attempt": 1,
    "maxAttempts": 3,
    "createdAt": "2026-02-25T10:00:00Z"
  }
}
```

### Subscription Filter (SQL)

```sql
-- Filter rule on be-worker subscription
workerType = 'be'
```

### Consumer Implementation

```java
@Component
@RequiredArgsConstructor
public class TaskMessageProcessor {
    private final IdempotentMessageHandler idempotency;
    private final TaskExecutor executor;

    @ServiceBusListener(
        topicName = "tasks",
        subscriptionName = "${worker.subscription}",
        concurrency = "${worker.maxConcurrentCalls:3}"
    )
    public void onMessage(ServiceBusReceivedMessageContext ctx) {
        var msg = ctx.getMessage();
        MDC.put("traceId", msg.getApplicationProperties()
            .getOrDefault("traceparent", msg.getMessageId()).toString());

        idempotency.tryProcess(msg.getMessageId(), () -> {
            var task = objectMapper.readValue(msg.getBody().toString(), TaskMessage.class);
            executor.execute(task);
        });

        ctx.complete();   // Remove from subscription
    }
}
```

### Dead-Letter Queue Handling

Messages are dead-lettered when:
1. Max delivery count exceeded (default: 10)
2. Consumer explicitly dead-letters (poison message detected)
3. TTL expired

```java
// Explicit dead-letter for unrecoverable errors
ctx.deadLetter(
    DeadLetterOptions.builder()
        .deadLetterReason("SCHEMA_MISMATCH")
        .deadLetterErrorDescription("Missing required field: taskKey")
        .build()
);
```

### DLQ Replay

```java
@Component
public class DlqReplayService {
    private final ServiceBusReceiverClient dlqReceiver;
    private final ServiceBusSenderClient sender;

    public int replay(int maxMessages) {
        int count = 0;
        for (var msg : dlqReceiver.receiveMessages(maxMessages, Duration.ofSeconds(5))) {
            var retry = new ServiceBusMessage(msg.getBody());
            retry.setMessageId(msg.getMessageId());
            msg.getApplicationProperties().forEach(
                (k, v) -> retry.getApplicationProperties().put(k, v));
            retry.getApplicationProperties().put("replayedAt", Instant.now().toString());
            sender.sendMessage(retry);
            dlqReceiver.complete(msg);
            count++;
        }
        return count;
    }
}
```

### Message Ordering

Service Bus topics do **not** guarantee ordering across partitions. If order matters:
- Use **sessions** (set `sessionId` to the plan ID) for per-plan ordering.
- Within a session, messages are delivered FIFO.

```java
msg.setSessionId(task.getPlanId());  // Guarantees FIFO within this plan
```

## Trade-offs

- **Pro**: Workers are fully decoupled from the orchestrator. They can scale independently and tolerate downtime.
- **Pro**: Built-in DLQ means failed messages are never lost; they can be inspected and replayed.
- **Con**: Async introduces eventual consistency. The orchestrator does not know a task succeeded until the worker sends a completion message back.
- **Con**: Message ordering requires sessions, which limit throughput (one active consumer per session).
- **Con**: At-least-once delivery requires idempotency on the consumer side (see outbox-and-idempotency pattern).
