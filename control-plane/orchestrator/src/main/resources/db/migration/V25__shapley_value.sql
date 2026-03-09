-- #40: Shapley Value per Reward Distribution (DAG-Aware)
-- Stores the computed Shapley value for each plan item after plan completion.
-- NULL until the DAG Shapley computation runs (all items DONE).
ALTER TABLE plan_items ADD COLUMN shapley_value DOUBLE PRECISION;
