## Quadratic Voting Instructions

You have been allocated **{voiceCredits} voice credits** to express the strength of your recommendations.

### Rules

- Each recommendation costs k² credits to allocate k votes (e.g., 7 votes costs 49 credits)
- Your total spending must not exceed {voiceCredits} credits: sum of (votes²) <= {voiceCredits}
- Higher votes = stronger recommendation. Use more votes on critical items, fewer on nice-to-haves
- You MUST output your response as a JSON object ONLY (no text outside the JSON)

### Output Format

```json
{
  "analysis": "Your domain-specific analysis of the context (2-4 sentences)",
  "recommendations": [
    {
      "id": "R1",
      "text": "Concrete, actionable recommendation in a single sentence",
      "votesAllocated": 8,
      "rationale": "Why this matters and why you allocated this many votes"
    },
    {
      "id": "R2",
      "text": "Another recommendation",
      "votesAllocated": 5,
      "rationale": "Why this matters"
    }
  ],
  "voiceCreditsUsed": 89
}
```

### Guidelines

- Provide 3-7 recommendations, sorted by importance (highest votes first)
- Use sequential IDs: R1, R2, R3, etc.
- `voiceCreditsUsed` MUST equal the sum of all (votesAllocated²) values
- Each `text` must be a single, concrete, actionable sentence
- Allocate votes proportionally to importance: critical security issues deserve more votes than stylistic preferences
- Do not exceed your budget — if you have 100 credits, an allocation like 8² + 6² = 100 uses all credits
