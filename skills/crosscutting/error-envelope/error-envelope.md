# Skill: Error Envelope

## Standard Error Response Format

All APIs MUST return errors using this envelope. Never invent custom error structures.

```json
{
  "code": "VALIDATION_FAILED",
  "message": "Human-readable summary (no PII)",
  "traceId": "64-hex-char OTel trace ID",
  "details": [
    { "field": "email", "reason": "must be a valid email address" }
  ]
}
```

## Error Codes

Use these codes exclusively. Do not invent new ones without updating the enum.

| Code | HTTP Status | When |
|------|-------------|------|
| `VALIDATION_FAILED` | 400 | Bean Validation / request body errors |
| `NOT_FOUND` | 404 | Entity lookup returned empty |
| `CONFLICT` | 409 | Unique constraint violation, optimistic lock failure |
| `UNAUTHORIZED` | 401 | Missing or invalid JWT |
| `FORBIDDEN` | 403 | Valid JWT but insufficient roles |
| `IDEMPOTENCY_CONFLICT` | 409 | Duplicate messageId detected |
| `INTERNAL_ERROR` | 500 | Unhandled exception (catch-all) |
| `SERVICE_UNAVAILABLE` | 503 | Downstream dependency unreachable |

## Spring Implementation

### Record

```java
package com.example.common.error;

import java.util.List;

public record ErrorEnvelope(
    String code,
    String message,
    String traceId,
    List<FieldError> details
) {
    public record FieldError(String field, String reason) {}

    public static ErrorEnvelope of(String code, String message, String traceId) {
        return new ErrorEnvelope(code, message, traceId, null);
    }
}
```

### Global Exception Handler

```java
package com.example.common.error;

import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.persistence.EntityNotFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorEnvelope> handleValidation(MethodArgumentNotValidException ex) {
        var details = ex.getBindingResult().getFieldErrors().stream()
            .map(f -> new ErrorEnvelope.FieldError(f.getField(), f.getDefaultMessage()))
            .toList();
        return ResponseEntity.badRequest().body(
            new ErrorEnvelope("VALIDATION_FAILED", "Request validation failed",
                MDC.get("traceId"), details));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorEnvelope> handleNotFound(EntityNotFoundException ex) {
        return ResponseEntity.status(404).body(
            ErrorEnvelope.of("NOT_FOUND", ex.getMessage(), MDC.get("traceId")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleGeneric(Exception ex) {
        // Always log the full stack trace for 500s
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(500).body(
            ErrorEnvelope.of("INTERNAL_ERROR", "An unexpected error occurred",
                MDC.get("traceId")));
    }
}
```

## Rules for Workers

1. Every `@RestController` method that can fail MUST rely on the global handler above. Do NOT catch exceptions in controllers just to format error responses.
2. The `message` field MUST NOT contain PII (emails, fiscal codes, IBANs). Use generic descriptions.
3. The `traceId` comes from MDC, populated by OTel. Never generate it manually.
4. When throwing business exceptions, create specific exception classes that extend `RuntimeException` and add `@ExceptionHandler` methods in the global handler.
5. The `details` array is ONLY for validation errors. For non-validation errors, set it to `null`.
