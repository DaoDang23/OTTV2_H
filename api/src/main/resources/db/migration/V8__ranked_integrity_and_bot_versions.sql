-- V8: Ranked integrity, bot version tracking, and match type constraints

-- 1. Ensure match_participants stores immutable bot version snapshot and checksum
ALTER TABLE match_participants ADD COLUMN IF NOT EXISTS source_sha256 CHAR(64);
ALTER TABLE match_participants ADD COLUMN IF NOT EXISTS version_no INT;

-- Backfill from submissions where available
UPDATE match_participants mp
SET source_sha256 = s.source_sha256, version_no = s.version_no
FROM submissions s
WHERE s.id = mp.submission_id AND mp.source_sha256 IS NULL;

-- 2. Support PRACTICE and FRIENDLY explicitly in user_matches
ALTER TABLE user_matches DROP CONSTRAINT IF EXISTS user_matches_mode_check;
ALTER TABLE user_matches ADD CONSTRAINT user_matches_mode_check
    CHECK (mode IN ('PVP', 'BOT', 'LADDER', 'PRACTICE', 'FRIENDLY'));

ALTER TABLE user_matches ADD COLUMN IF NOT EXISTS match_type VARCHAR(16) DEFAULT 'RANKED';

-- 3. Add index on user_matches (user_id, is_ranked) for fast ranked stats aggregation
CREATE INDEX IF NOT EXISTS idx_user_matches_ranked ON user_matches(user_id, is_ranked, created_at DESC);
