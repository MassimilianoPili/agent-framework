-- ============================================================================
-- V9: DPO GP Residual
-- Aggiunge campo gp_residual a preference_pairs per la strategia
-- gp_residual_surprise. Misura |actual_reward - gp_predicted| del chosen item.
-- ============================================================================

ALTER TABLE preference_pairs ADD COLUMN gp_residual FLOAT;

CREATE INDEX idx_pp_gp_residual ON preference_pairs (gp_residual DESC NULLS LAST);

COMMENT ON COLUMN preference_pairs.gp_residual IS
    'GP residual |actual - predicted| del chosen item. Alto = coppia informativa (sorpresa GP). NULL per strategie non-GP.';
