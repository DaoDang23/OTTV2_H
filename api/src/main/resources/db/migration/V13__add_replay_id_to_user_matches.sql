-- =============================================================================
-- V13: Add replay_id column to user_matches for replay synchronization
-- =============================================================================

ALTER TABLE user_matches ADD COLUMN IF NOT EXISTS replay_id VARCHAR(120);
