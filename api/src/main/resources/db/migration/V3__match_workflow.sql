CREATE INDEX idx_matches_participant_status ON match_participants(bot_id, match_id DESC, created_month);
ALTER TABLE matches ADD COLUMN failure_detail TEXT;

-- The result consumer is idempotent by match id + partition key. Keep replay metadata
-- addressable through the same partition key as matches.
ALTER TABLE replays ADD COLUMN created_month DATE;
UPDATE replays r SET created_month = m.created_month
FROM matches m WHERE m.id = r.match_id;
ALTER TABLE replays ALTER COLUMN created_month SET NOT NULL;
ALTER TABLE replays DROP CONSTRAINT replays_pkey;
ALTER TABLE replays ADD PRIMARY KEY (match_id, created_month);
ALTER TABLE replays ADD CONSTRAINT fk_replays_match
    FOREIGN KEY (match_id, created_month) REFERENCES matches(id, created_month);
