-- =============================================================================
-- V12: Expand tournament status and format columns to accommodate full lifecycle
-- In V1 schema, tournaments.status was defined as VARCHAR(16).
-- Values 'REGISTRATION_OPEN' (17 chars) and 'REGISTRATION_CLOSED' (19 chars)
-- cause value-too-long DataIntegrityViolationException without this expansion.
-- =============================================================================

ALTER TABLE tournaments ALTER COLUMN status TYPE VARCHAR(32);
ALTER TABLE tournaments ALTER COLUMN format TYPE VARCHAR(32);
