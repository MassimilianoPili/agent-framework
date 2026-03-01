# Quality Gate Report

Evaluate the execution results for plan `{{PLAN_ID}}`.

## Task Results

```json
{{ALL_RESULTS_JSON}}
```

## Instructions

For each completed task, assess:
1. **Correctness** — Does the output meet the task description and acceptance criteria?
2. **Completeness** — Are all deliverables present?
3. **Quality** — Code quality, test coverage, documentation.

## Output

Respond with a JSON object:
```json
{
  "planId": "...",
  "overallScore": 0-100,
  "pass": true/false,
  "coverageThreshold": {{COVERAGE_THRESHOLD}},
  "items": [
    {
      "taskKey": "BE-001",
      "score": 85,
      "pass": true,
      "feedback": "Implementation complete, tests passing."
    }
  ],
  "summary": "One-line overall assessment."
}
```

A task passes if its score >= {{COVERAGE_THRESHOLD}}. The plan passes if ALL tasks pass.
