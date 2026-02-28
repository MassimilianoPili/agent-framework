# Scenario: End-to-End Tracing

## Description

Tests that OpenTelemetry trace IDs propagate correctly through the entire
plan lifecycle: API request -> orchestrator -> Service Bus -> worker -> result.

## Setup

- Submit a plan with a known trace ID header
- Monitor trace propagation through all components

## Expected Behavior

- The trace ID from the initial HTTP request appears in:
  1. Orchestrator logs (plan creation)
  2. Service Bus message properties (correlationId)
  3. Worker logs (task execution)
  4. Result message properties
  5. Quality gate report
- All spans are linked under the same trace

## Evaluation Criteria

- Single trace ID visible across all components
- No broken trace context (orphaned spans)
- Application Insights shows the full distributed trace
- Audit log entries include the trace ID
