# Error Handling and Response Envelopes

## Problem
Without a standard error format, every service invents its own structure. Consumers must write ad-hoc parsing for each endpoint, and debugging across services becomes painful because error responses lack correlation data.

## Solution
Define a single **error envelope** returned by all APIs on 4xx/5xx responses. Use Spring `@ControllerAdvice` to intercept exceptions globally and map them to the envelope.

### Error Envelope Schema

```json
{
  "code": "VALIDATION_FAILED",
  "message": "Human-readable summary of the error",
  "traceId": "abc123-def456-...",
  "details": [
    {
      "field": "email",
      "reason": "must be a valid email address"
    }
  ]
}
```

| Field     | Type     | Required | Description |
|-----------|----------|----------|-------------|
| `code`    | string   | Yes      | Machine-readable error code (enum) |
| `message` | string   | Yes      | Human-readable message (never includes PII) |
| `traceId` | string   | Yes      | OpenTelemetry trace ID from MDC |
| `details` | array    | No       | Field-level errors for validation failures |

### Code Example

```java
public record ErrorEnvelope(
    String code,
    String message,
    String traceId,
    List<FieldError> details
) {
    public record FieldError(String field, String reason) {}
}

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorEnvelope> handleValidation(
            MethodArgumentNotValidException ex) {
        var details = ex.getBindingResult().getFieldErrors().stream()
            .map(f -> new ErrorEnvelope.FieldError(f.getField(), f.getDefaultMessage()))
            .toList();
        var envelope = new ErrorEnvelope(
            "VALIDATION_FAILED",
            "Request validation failed",
            MDC.get("traceId"),
            details
        );
        return ResponseEntity.badRequest().body(envelope);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorEnvelope> handleNotFound(EntityNotFoundException ex) {
        var envelope = new ErrorEnvelope(
            "NOT_FOUND",
            ex.getMessage(),
            MDC.get("traceId"),
            null
        );
        return ResponseEntity.status(404).body(envelope);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        var envelope = new ErrorEnvelope(
            "INTERNAL_ERROR",
            "An unexpected error occurred",
            MDC.get("traceId"),
            null
        );
        return ResponseEntity.status(500).body(envelope);
    }
}
```

### Error Codes (Enum)

```java
public enum ErrorCode {
    VALIDATION_FAILED,
    NOT_FOUND,
    CONFLICT,
    UNAUTHORIZED,
    FORBIDDEN,
    INTERNAL_ERROR,
    SERVICE_UNAVAILABLE,
    IDEMPOTENCY_CONFLICT
}
```

## Trade-offs

- **Pro**: Uniform parsing on all consumers (frontend, other services, agents). The `traceId` enables instant log correlation.
- **Pro**: `@ControllerAdvice` is a single interception point; individual controllers stay clean.
- **Con**: Generic `message` field may not carry enough context for complex domain errors. Mitigated by `details` array and specific `code` values.
- **Con**: The catch-all `Exception` handler can mask bugs if developers forget to throw specific exceptions. Mitigated by logging the full stack trace.
