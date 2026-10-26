ALTER TABLE users ADD COLUMN IF NOT EXISTS pvp_elo INT NOT NULL DEFAULT 1200;
ALTER TABLE users ADD COLUMN IF NOT EXISTS bot_elo INT NOT NULL DEFAULT 1200;

CREATE TABLE IF NOT EXISTS user_matches (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('PVP', 'BOT', 'LADDER')),
    opponent VARCHAR(100) NOT NULL,
    opponent_elo INT NOT NULL DEFAULT 1200,
    opponent_type VARCHAR(32),
    user_won BOOLEAN NOT NULL,
    winner VARCHAR(16) NOT NULL,
    reason VARCHAR(32),
    elo_change INT NOT NULL,
    new_elo INT NOT NULL,
    is_ranked BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_user_matches_user_created ON user_matches(user_id, created_at DESC);
