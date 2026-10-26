-- V7: Add soft delete support for bots
ALTER TABLE bots ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE;
