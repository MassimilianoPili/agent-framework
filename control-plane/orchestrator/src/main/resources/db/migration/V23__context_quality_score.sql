-- #35: Context Quality Scoring — information-theoretic feedback on context quality
ALTER TABLE task_outcomes ADD COLUMN context_quality_score DOUBLE PRECISION;
