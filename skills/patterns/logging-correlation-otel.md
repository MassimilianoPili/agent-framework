# Logging Correlation with OpenTelemetry

## Problem
In a distributed system with multiple services and async messaging (Service Bus), a single user action generates logs across many processes. Without correlation, tracing a request end-to-end requires manual timestamp matching — slow and unreliable.

## Solution
Use **OpenTelemetry (OTel)** for distributed tracing. Propagate `traceId` and `spanId` through HTTP headers and Service Bus message properties. Inject them into **SLF4J MDC** so every log line carries the trace context automatically.

### Architecture

```
Browser -> Spring Boot A -> Service Bus -> Spring Boot B
           [traceId=X]       [traceId=X]    [traceId=X]
           MDC.traceId=X     msg.prop.tp=X  MDC.traceId=X
```

### Spring Boot Configuration

```yaml
# application.yml
management:
  tracing:
    sampling:
      probability: 1.0          # Sample all traces in dev; reduce in prod
  otlp:
    tracing:
      endpoint: http://otel-collector:4318/v1/traces

logging:
  pattern:
    console: "%d{ISO8601} [%thread] %-5level %logger{36} [traceId=%X{traceId} spanId=%X{spanId}] - %msg%n"
  structured:
    format: ecs                 # Elastic Common Schema JSON format
```

```xml
<!-- pom.xml dependencies -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

### Service Bus Trace Propagation

When sending a message, inject the current trace context into message application properties. When receiving, extract and restore it.

```java
// Sender — inject trace context into Service Bus message
@Component
@RequiredArgsConstructor
public class TracingMessageSender {
    private final ServiceBusSenderClient sender;
    private final Tracer tracer;

    public void send(String body, Map<String, Object> properties) {
        var msg = new ServiceBusMessage(body);
        // Propagate W3C traceparent
        var span = tracer.currentSpan();
        if (span != null) {
            msg.getApplicationProperties().put("traceparent", span.context().traceId());
            msg.getApplicationProperties().put("spanId", span.context().spanId());
        }
        properties.forEach((k, v) -> msg.getApplicationProperties().put(k, v));
        sender.sendMessage(msg);
    }
}

// Receiver — extract trace context and set MDC
@Component
public class TracingMessageProcessor {

    public void process(ServiceBusReceivedMessageContext ctx) {
        var props = ctx.getMessage().getApplicationProperties();
        var traceId = (String) props.getOrDefault("traceparent", UUID.randomUUID().toString());
        MDC.put("traceId", traceId);
        try {
            // delegate to business logic
        } finally {
            MDC.clear();
        }
    }
}
```

### Structured Log Output (JSON)

```json
{
  "@timestamp": "2026-02-25T10:15:30.123Z",
  "log.level": "INFO",
  "message": "Order created",
  "trace.id": "abc123def456",
  "span.id": "789ghi",
  "service.name": "order-service",
  "order.id": "ORD-001"
}
```

### Correlation ID for Business Context

In addition to OTel `traceId`, propagate a business `correlationId` (e.g., plan ID) for agent framework traceability:

```java
MDC.put("correlationId", taskMessage.getPlanId());
MDC.put("taskKey", taskMessage.getTaskKey());
```

## Trade-offs

- **Pro**: Every log line is searchable by `traceId` across all services. Debugging async flows becomes straightforward.
- **Pro**: Spring Boot 3 auto-configures OTel bridge with Micrometer; minimal boilerplate.
- **Con**: 100% sampling in production generates high volume. Use tail-based sampling or reduce probability.
- **Con**: Service Bus does not natively propagate W3C traceparent; manual injection is required (as shown above).
