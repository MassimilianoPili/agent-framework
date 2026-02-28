# Skill: OpenTelemetry with Spring Boot

## Dependencies

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-spring-boot-starter</artifactId>
</dependency>
```

## Configuration

```yaml
# application.yml
spring:
  application:
    name: ${SERVICE_NAME:my-service}

management:
  tracing:
    sampling:
      probability: ${TRACE_SAMPLING:1.0}   # 1.0 in dev, 0.1 in prod
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://otel-collector:4318/v1/traces}

logging:
  pattern:
    console: >-
      %d{ISO8601} [%thread] %-5level %logger{36}
      [traceId=%X{traceId} spanId=%X{spanId} planId=%X{planId}]
      - %msg%n
```

## Trace Propagation via Service Bus

Spring auto-instruments HTTP calls. Service Bus requires manual propagation.

### Sender Side

```java
@Component
@RequiredArgsConstructor
public class TracedMessageSender {
    private final ServiceBusSenderClient sender;
    private final Tracer tracer;

    public void send(String topic, String body, String planId, String taskKey) {
        var msg = new ServiceBusMessage(body);
        msg.setMessageId(UUID.randomUUID().toString());

        // Propagate trace context
        var span = tracer.currentSpan();
        if (span != null) {
            var ctx = span.context();
            msg.getApplicationProperties().put("traceparent",
                String.format("00-%s-%s-01", ctx.traceId(), ctx.spanId()));
        }

        // Business correlation
        msg.getApplicationProperties().put("planId", planId);
        msg.getApplicationProperties().put("taskKey", taskKey);

        sender.sendMessage(msg);
    }
}
```

### Receiver Side

```java
@Component
public class TracedMessageReceiver {

    public void onMessage(ServiceBusReceivedMessageContext ctx) {
        var props = ctx.getMessage().getApplicationProperties();

        // Restore trace context into MDC
        MDC.put("traceId", extractTraceId(props));
        MDC.put("planId", (String) props.getOrDefault("planId", "unknown"));
        MDC.put("taskKey", (String) props.getOrDefault("taskKey", "unknown"));

        try {
            // Business logic here
        } finally {
            MDC.clear();
        }
    }

    private String extractTraceId(Map<String, Object> props) {
        var traceparent = (String) props.get("traceparent");
        if (traceparent != null && traceparent.contains("-")) {
            return traceparent.split("-")[1];  // Extract trace ID from W3C format
        }
        return UUID.randomUUID().toString();
    }
}
```

## Structured JSON Logs (Production)

```yaml
# application-prod.yml
logging:
  structured:
    format: ecs    # Elastic Common Schema
```

Output:
```json
{
  "@timestamp": "2026-02-25T10:15:30.123Z",
  "log.level": "INFO",
  "message": "Task completed",
  "service.name": "be-worker",
  "trace.id": "abc123...",
  "planId": "PLAN-42",
  "taskKey": "be-user-service"
}
```

## Custom Spans

```java
@Service
@RequiredArgsConstructor
public class UserService {
    private final ObservationRegistry registry;

    public User createUser(CreateUserRequest req) {
        return Observation.createNotStarted("user.create", registry)
            .lowCardinalityKeyValue("user.type", req.type().name())
            .observe(() -> {
                // Business logic — this block is timed as a span
                return userRepository.save(User.from(req));
            });
    }
}
```

## Rules for Workers

1. NEVER remove or modify the OTel dependencies or configuration.
2. Every Service Bus message send/receive MUST propagate trace context as shown above.
3. Use `MDC.put("planId", ...)` at the entry point of every task execution.
4. Custom spans (via `Observation`) should be added for significant business operations (DB writes, external calls), not for trivial methods.
5. Never log at DEBUG level in production profiles. Use INFO for business events, WARN for recoverable issues, ERROR for failures.
