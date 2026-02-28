# ADR-002: Structured Output via BeanOutputConverter

## Status

Accepted

## Context

The planner service uses Claude LLM to decompose natural language specs into
structured execution plans. We need a reliable way to ensure the LLM output
conforms to our Plan/PlanItem schema.

Options considered:
1. **Prompt-only approach**: Ask the LLM to output JSON and parse manually
2. **JSON Schema in system prompt**: Provide the schema and hope for compliance
3. **Spring AI BeanOutputConverter**: Use Spring AI's built-in structured output
4. **Function calling**: Use Claude's tool_use to return structured data

## Decision

We chose **Option 3: Spring AI BeanOutputConverter**.

## Rationale

- **Type safety**: Java records (`PlanSchema`, `PlanItemSchema`) define the
  structure at compile time. The converter generates JSON Schema from the
  records and instructs the LLM to comply.
- **Automatic parsing**: `BeanOutputConverter<PlanSchema>` handles JSON
  extraction and deserialization without manual parsing.
- **Validation**: If the LLM output does not match the schema, a clear
  exception is thrown rather than silent data corruption.
- **Spring AI ecosystem**: Consistent with the rest of the Spring AI
  integration (ChatClient, advisors, etc.).

## Trade-offs

- Couples to Spring AI's BeanOutputConverter implementation
- Less flexible than raw function calling for complex nested structures
- Schema generation from Java records may not capture all constraints
  (e.g., pattern validation on taskKey)

## Consequences

- `PlannerService` uses `ChatClient` with `.entity(PlanSchema.class)`
- `PlanSchema` and `PlanItemSchema` are Java records in the planner package
- The JSON Schema is automatically injected into the system prompt
- `contracts/agent-schemas/Plan.schema.json` serves as documentation
  but the runtime schema comes from the Java records
