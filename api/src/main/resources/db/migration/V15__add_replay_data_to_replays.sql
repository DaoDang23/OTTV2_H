-- V15: Add replay_data column to replays table for direct database replay storage
ALTER TABLE replays ADD COLUMN IF NOT EXISTS replay_data JSONB;
