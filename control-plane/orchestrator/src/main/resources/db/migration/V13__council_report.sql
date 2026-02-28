-- V13: Add council_report column to plans table
-- Stores the JSON CouncilReport produced by the pre-planning council session.
-- Null when council.enabled=false or for plans created before this migration.

ALTER TABLE plans ADD COLUMN IF NOT EXISTS council_report TEXT;
