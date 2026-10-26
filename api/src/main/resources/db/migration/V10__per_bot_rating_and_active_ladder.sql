-- V10: Per-Bot Rating and Active Ladder Mechanism

-- 1. Ensure at least one active season exists for bot ratings
INSERT INTO seasons (id, name, starts_at, ends_at, status)
VALUES (1, 'Season 1', now() - interval '30 days', now() + interval '335 days', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- 2. Ensure ladder_enabled is default true
ALTER TABLE bots ALTER COLUMN ladder_enabled SET DEFAULT TRUE;

-- 3. Auto-populate active_submission_id for any bots where it is currently NULL
UPDATE bots b
SET active_submission_id = s.id
FROM (
    SELECT DISTINCT ON (bot_id) id, bot_id
    FROM submissions
    WHERE status IN ('ACTIVE', 'COMPILED', 'PENDING')
    ORDER BY bot_id, version_no DESC
) s
WHERE b.id = s.bot_id AND b.active_submission_id IS NULL;

-- 4. Backfill default bot_ratings (1200 ELO) for all existing bots missing rating records
INSERT INTO bot_ratings(bot_id, season_id, elo, games, wins, losses, draws, version, updated_at)
SELECT b.id,
       COALESCE((SELECT s.id FROM seasons s WHERE s.status = 'ACTIVE' ORDER BY s.starts_at DESC LIMIT 1), 1),
       1200, 0, 0, 0, 0, 0, now()
FROM bots b
WHERE NOT EXISTS (
    SELECT 1 FROM bot_ratings br WHERE br.bot_id = b.id
)
ON CONFLICT (bot_id, season_id) DO NOTHING;

-- 5. Useful index for fast leaderboard lookups
CREATE INDEX IF NOT EXISTS idx_bot_ratings_bot_updated ON bot_ratings(bot_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_bot_ratings_season_elo ON bot_ratings(season_id, elo DESC);
