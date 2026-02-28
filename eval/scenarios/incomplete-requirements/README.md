# Scenario: Incomplete Requirements

## Description

Tests the planner's ability to handle vague or incomplete specifications.
The input spec deliberately omits critical details (database choice, auth method,
error handling requirements) to verify that the planner either:
1. Generates reasonable defaults based on project conventions
2. Creates a plan item to clarify requirements before proceeding

## Input

```json
{
  "spec": "Build a user API"
}
```

## Expected Behavior

- Planner should infer reasonable defaults (PostgreSQL, JWT, REST)
- Plan should include a BE task for the API and at least one test task
- Plan should NOT fail with an error due to insufficient detail
- Quality gate report should pass all gates

## Evaluation Criteria

- Plan contains at least 3 items
- All items reach COMPLETED status
- Generated code compiles and tests pass
