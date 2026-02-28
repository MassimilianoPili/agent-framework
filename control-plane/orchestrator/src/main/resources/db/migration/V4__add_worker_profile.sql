-- Worker Multi-Stack: add worker_profile column for stack-specific routing.
-- NULL = use default profile from WorkerProfileRegistry (e.g. BE -> be-java).
ALTER TABLE plan_items ADD COLUMN worker_profile VARCHAR(50);
