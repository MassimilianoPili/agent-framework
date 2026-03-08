-- V17: plan_outcomes — GP training data for Council Taste Profile (#13)
-- Records structural features + actual reward of completed plans.
-- A Gaussian Process is fitted on these 5 features to predict plan quality.

CREATE TABLE plan_outcomes (
    id               UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id          UUID      NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    n_tasks          INT       NOT NULL,
    has_context_task BOOLEAN   NOT NULL DEFAULT FALSE,
    has_review_task  BOOLEAN   NOT NULL DEFAULT FALSE,
    n_be_tasks       INT       NOT NULL DEFAULT 0,
    n_fe_tasks       INT       NOT NULL DEFAULT 0,
    actual_reward    FLOAT,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_plan_outcomes_reward ON plan_outcomes(actual_reward)
    WHERE actual_reward IS NOT NULL;
