-- V20: Add authoritative PvP participant IDs and room code to matches table
ALTER TABLE matches ADD COLUMN IF NOT EXISTS user_a_id BIGINT REFERENCES users(id);
ALTER TABLE matches ADD COLUMN IF NOT EXISTS user_b_id BIGINT REFERENCES users(id);
ALTER TABLE matches ADD COLUMN IF NOT EXISTS room_code VARCHAR(16);

CREATE INDEX IF NOT EXISTS idx_matches_user_a ON matches(user_a_id);
CREATE INDEX IF NOT EXISTS idx_matches_user_b ON matches(user_b_id);
CREATE INDEX IF NOT EXISTS idx_matches_room_code ON matches(room_code);
