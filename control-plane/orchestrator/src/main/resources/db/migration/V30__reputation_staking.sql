-- #47 Reputation Staking: game-theoretic accountability for worker profiles.
-- Workers stake a portion of their ELO reputation before task dispatch.
-- Success → bonus, failure → stake forfeited.

ALTER TABLE worker_elo_stats ADD COLUMN staked_reputation DOUBLE PRECISION NOT NULL DEFAULT 0.0;
ALTER TABLE worker_elo_stats ADD COLUMN total_staked DOUBLE PRECISION NOT NULL DEFAULT 0.0;
ALTER TABLE worker_elo_stats ADD COLUMN total_forfeited DOUBLE PRECISION NOT NULL DEFAULT 0.0;

ALTER TABLE plan_items ADD COLUMN staked_amount DOUBLE PRECISION;
