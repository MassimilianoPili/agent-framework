# Scenario: Partial Failure Recovery

## Description

Tests the orchestrator's ability to handle partial failures gracefully.
One worker task is designed to fail (e.g., by referencing a non-existent dependency),
and the orchestrator must:
1. Mark the failed task appropriately
2. Continue executing independent tasks
3. Report the partial failure in the quality gate

## Setup

- Inject a task that will fail (e.g., import from non-existent module)
- Ensure other tasks in the plan are independent of the failing task

## Expected Behavior

- Failed task: status = FAILED with error message
- Independent tasks: status = COMPLETED
- Dependent tasks: status = FAILED (cascading)
- Plan status: FAILED (partial completion)
- Quality gate: reports which tasks failed and why

## Evaluation Criteria

- Orchestrator does not crash or hang
- Independent tasks complete despite the failure
- DLQ receives the failed message after max retries
- Audit log captures the failure chain
