-- V11: Matchmaking Queue and READY/STARTING Status

-- 1. Allow READY and STARTING status in matches table for matchmaking workflow
ALTER TABLE matches DROP CONSTRAINT IF EXISTS matches_status_check;
ALTER TABLE matches ADD CONSTRAINT matches_status_check 
  CHECK (status IN ('QUEUED','READY','STARTING','RUNNING','FINISHED','ERROR','CANCELLED'));

-- 2. Allow PVP and BOT match types in matches table
ALTER TABLE matches DROP CONSTRAINT IF EXISTS matches_match_type_check;
ALTER TABLE matches ADD CONSTRAINT matches_match_type_check
  CHECK (match_type IN ('LADDER','TOURNAMENT','SMOKE','FRIENDLY','PVP','BOT'));
