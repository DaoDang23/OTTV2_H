-- =============================================================================
-- V9: Tournament Engine (Lifecycle, Teams, Members, Matches, Audit Logs)
-- =============================================================================

-- 1. Upgrade tournaments table
ALTER TABLE tournaments ALTER COLUMN season_id DROP NOT NULL;
ALTER TABLE tournaments ADD COLUMN IF NOT EXISTS title VARCHAR(255);
ALTER TABLE tournaments ADD COLUMN IF NOT EXISTS slug VARCHAR(120);
ALTER TABLE tournaments ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE tournaments ADD COLUMN IF NOT EXISTS max_teams INT DEFAULT 16;
ALTER TABLE tournaments ADD COLUMN IF NOT EXISTS team_size INT NOT NULL DEFAULT 1;
ALTER TABLE tournaments ADD COLUMN IF NOT EXISTS rules_config JSONB DEFAULT '{}'::jsonb;
ALTER TABLE tournaments ADD COLUMN IF NOT EXISTS created_by BIGINT REFERENCES users(id);
ALTER TABLE tournaments ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE tournaments ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Backfill title and slug for existing rows if any
UPDATE tournaments SET title = name WHERE title IS NULL;
UPDATE tournaments SET slug = 'tournament-' || id WHERE slug IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_tournaments_slug ON tournaments(slug);

-- Update status check constraint for full lifecycle (including FINISHED for legacy result service compatibility)
ALTER TABLE tournaments DROP CONSTRAINT IF EXISTS tournaments_status_check;
ALTER TABLE tournaments ADD CONSTRAINT tournaments_status_check CHECK (status IN (
    'DRAFT', 'REGISTRATION_OPEN', 'REGISTRATION_CLOSED', 'CHECK_IN', 'RUNNING', 'COMPLETED', 'FINISHED', 'SUSPENDED', 'CANCELLED'
));

-- Update format check constraint
ALTER TABLE tournaments DROP CONSTRAINT IF EXISTS tournaments_format_check;
ALTER TABLE tournaments ADD CONSTRAINT tournaments_format_check CHECK (format IN (
    'SINGLE_ELIMINATION', 'ROUND_ROBIN', 'SWISS'
));

-- 2. Tournament Teams
CREATE TABLE IF NOT EXISTS tournament_teams (
    id BIGSERIAL PRIMARY KEY,
    tournament_id BIGINT NOT NULL REFERENCES tournaments(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    team_code VARCHAR(32),
    captain_id BIGINT NOT NULL REFERENCES users(id),
    bot_id BIGINT REFERENCES bots(id),
    submission_id BIGINT REFERENCES submissions(id),
    source_sha256 VARCHAR(64),
    version_no INT,
    status VARCHAR(20) NOT NULL DEFAULT 'REGISTERED' CHECK (status IN ('REGISTERED', 'VERIFIED', 'CHECKED_IN', 'DISQUALIFIED', 'ELIMINATED')),
    seed INT,
    score DECIMAL(6,2) NOT NULL DEFAULT 0,
    buchholz_score DECIMAL(6,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tourney_team_name UNIQUE (tournament_id, name),
    CONSTRAINT uq_tourney_team_captain UNIQUE (tournament_id, captain_id),
    CONSTRAINT uq_team_tourney_id UNIQUE (id, tournament_id)
);
CREATE INDEX IF NOT EXISTS idx_tournament_teams_tourney ON tournament_teams(tournament_id, status);

-- 3. Tournament Members (Enforces single team per user within a tournament)
CREATE TABLE IF NOT EXISTS tournament_members (
    team_id BIGINT NOT NULL,
    tournament_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL REFERENCES users(id),
    role VARCHAR(20) NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('CAPTAIN', 'MEMBER')),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (team_id, user_id),
    CONSTRAINT uq_tournament_user UNIQUE (tournament_id, user_id),
    CONSTRAINT fk_tournament_members_team FOREIGN KEY (team_id, tournament_id) REFERENCES tournament_teams(id, tournament_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_tournament_members_user ON tournament_members(user_id);

-- 4. Tournament Matches (Linked to matches table via (match_id, match_created_month) when executed)
CREATE TABLE IF NOT EXISTS tournament_matches (
    id BIGSERIAL PRIMARY KEY,
    tournament_id BIGINT NOT NULL REFERENCES tournaments(id) ON DELETE CASCADE,
    round_no INT NOT NULL,
    match_no INT NOT NULL,
    stage VARCHAR(32) NOT NULL DEFAULT 'KNOCKOUT',
    match_id BIGINT,
    match_created_month DATE,
    team_a_id BIGINT REFERENCES tournament_teams(id),
    team_b_id BIGINT REFERENCES tournament_teams(id),
    winner_team_id BIGINT REFERENCES tournament_teams(id),
    status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'READY', 'RUNNING', 'FINISHED', 'BYE', 'ERROR', 'CANCELLED')),
    score_a INT NOT NULL DEFAULT 0,
    score_b INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_tournament_matches_real_match FOREIGN KEY (match_id, match_created_month) REFERENCES matches(id, created_month) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_tournament_matches_lookup ON tournament_matches(tournament_id, round_no, match_no);
CREATE INDEX IF NOT EXISTS idx_tournament_matches_real_match ON tournament_matches(match_id, match_created_month) WHERE match_id IS NOT NULL;

-- 5. Tournament Audit Logs
CREATE TABLE IF NOT EXISTS tournament_audit_logs (
    id BIGSERIAL PRIMARY KEY,
    tournament_id BIGINT NOT NULL REFERENCES tournaments(id) ON DELETE CASCADE,
    operator_id BIGINT REFERENCES users(id),
    action VARCHAR(64) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_tournament_audit_logs ON tournament_audit_logs(tournament_id, created_at DESC);
