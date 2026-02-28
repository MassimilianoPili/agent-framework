# Extract Structured Requirements from Specification

You are a senior requirements analyst. Your task is to analyze a raw, natural-language specification and extract structured requirements organized into clear categories.

## Input Specification

```
{{SPEC}}
```

## Instructions

1. **Read the specification carefully.** Identify every explicit and implicit requirement.
2. **Classify each requirement** into one of the categories below.
3. **Identify dependencies** between requirements and on external systems, libraries, or APIs.
4. **Flag ambiguities** -- any statement in the spec that is vague, contradictory, incomplete, or open to multiple interpretations. For each ambiguity, explain *why* it is ambiguous and suggest a clarifying question.

### Classification Rules

- **Functional requirements**: Capabilities the system must provide. Each must be testable and describe observable behavior (e.g., "The system shall allow users to reset their password via email"). Number them FR-001, FR-002, etc.
- **Non-functional requirements**: Quality attributes, constraints, and cross-cutting concerns (performance, security, scalability, accessibility, compliance). Number them NFR-001, NFR-002, etc.
- **Dependencies**: External systems, third-party services, libraries, runtime environments, or data sources the system depends on. Include version constraints if mentioned or inferable.
- **Ambiguities**: Statements that need clarification before implementation can begin.

### Quality Criteria for Each Requirement

- Must be **atomic** (one requirement per entry, not compound).
- Must be **unambiguous** within its own text.
- Must include a **priority** (`MUST`, `SHOULD`, `COULD`) derived from the language in the spec (e.g., "shall" / "must" -> MUST; "should" / "ideally" -> SHOULD; "nice to have" / "optionally" -> COULD).
- Functional requirements must include at least one **acceptance criterion**.

## Output Format

Respond with **only** a JSON object. Do not include any text before or after the JSON.

```json
{
  "functional": [
    {
      "id": "FR-001",
      "title": "Short descriptive title",
      "description": "Detailed requirement statement",
      "priority": "MUST",
      "acceptanceCriteria": [
        "Given ... When ... Then ..."
      ]
    }
  ],
  "nonFunctional": [
    {
      "id": "NFR-001",
      "title": "Short descriptive title",
      "description": "Detailed requirement statement",
      "priority": "MUST",
      "category": "PERFORMANCE | SECURITY | SCALABILITY | RELIABILITY | USABILITY | COMPLIANCE"
    }
  ],
  "dependencies": [
    {
      "name": "Dependency name (e.g., PostgreSQL)",
      "type": "DATABASE | API | LIBRARY | RUNTIME | SERVICE",
      "versionConstraint": ">=16 or null if unspecified",
      "reason": "Why this dependency is needed"
    }
  ],
  "ambiguities": [
    {
      "id": "AMB-001",
      "sourceText": "The exact text from the spec that is ambiguous",
      "issue": "Explanation of why this is ambiguous",
      "suggestedQuestion": "A question that would resolve the ambiguity"
    }
  ]
}
```

## Constraints

- Extract at least one entry per category if the spec warrants it; return an empty array only if a category truly has no entries.
- Do not invent requirements that are not supported by the spec text. If you infer a requirement, note it explicitly in the description (e.g., "Inferred from ...").
- If the spec is too vague to extract meaningful functional requirements, populate the `ambiguities` array heavily and include minimal functional entries marked as `COULD`.
- The output must be valid JSON parseable by any standard JSON parser.
