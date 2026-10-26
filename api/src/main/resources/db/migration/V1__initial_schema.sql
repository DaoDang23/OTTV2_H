CREATE TABLE users (
  id BIGSERIAL PRIMARY KEY,
  student_code VARCHAR(32) UNIQUE NOT NULL,
  email VARCHAR(255) UNIQUE NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  role VARCHAR(16) NOT NULL DEFAULT 'STUDENT' CHECK (role IN ('STUDENT','ADMIN','JUDGE')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE bots (
  id BIGSERIAL PRIMARY KEY,
  owner_id BIGINT NOT NULL REFERENCES users(id),
  name VARCHAR(64) NOT NULL,
  active_submission_id BIGINT,
  ladder_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (owner_id, name)
);

CREATE TABLE submissions (
  id BIGSERIAL PRIMARY KEY,
  bot_id BIGINT NOT NULL REFERENCES bots(id),
  version_no INT NOT NULL,
  language VARCHAR(16) NOT NULL CHECK (language IN ('JAVA','CPP','PYTHON','KOTLIN','JS')),
  source_key VARCHAR(255) NOT NULL,
  source_sha256 CHAR(64) NOT NULL,
  artifact_key VARCHAR(255),
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','COMPILING','COMPILE_ERROR','SMOKE_TESTING','ACTIVE','REJECTED','RETIRED')),
  compile_log TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (bot_id, version_no)
);
CREATE INDEX idx_sub_bot_created ON submissions(bot_id, created_at DESC);
CREATE INDEX idx_sub_sha ON submissions(source_sha256);
ALTER TABLE bots ADD CONSTRAINT fk_bots_active FOREIGN KEY (active_submission_id) REFERENCES submissions(id);

CREATE TABLE seasons (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  starts_at TIMESTAMPTZ NOT NULL,
  ends_at TIMESTAMPTZ NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','FROZEN','ARCHIVED'))
);

CREATE TABLE ladder_windows (
  id BIGSERIAL PRIMARY KEY,
  season_id BIGINT NOT NULL REFERENCES seasons(id),
  day_of_week SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
  start_time TIME NOT NULL,
  end_time TIME NOT NULL,
  timezone VARCHAR(40) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh'
);

CREATE TABLE bot_ratings (
  bot_id BIGINT NOT NULL REFERENCES bots(id),
  season_id BIGINT NOT NULL REFERENCES seasons(id),
  elo INT NOT NULL DEFAULT 1200,
  games INT NOT NULL DEFAULT 0,
  wins INT NOT NULL DEFAULT 0,
  losses INT NOT NULL DEFAULT 0,
  draws INT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (bot_id, season_id)
);
CREATE INDEX idx_rating_rank ON bot_ratings(season_id, elo DESC);

CREATE TABLE matches (
  id BIGSERIAL,
  public_id UUID NOT NULL DEFAULT gen_random_uuid(),
  season_id BIGINT REFERENCES seasons(id),
  match_type VARCHAR(12) NOT NULL CHECK (match_type IN ('LADDER','TOURNAMENT','SMOKE','FRIENDLY')),
  seed BIGINT NOT NULL,
  map_config JSONB NOT NULL,
  engine_ver VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RUNNING','FINISHED','ERROR','CANCELLED')),
  winner_side CHAR(1) CHECK (winner_side IN ('A','B')),
  end_reason VARCHAR(24),
  total_turns INT,
  worker_id VARCHAR(64),
  queued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ,
  created_month DATE NOT NULL DEFAULT date_trunc('month', now())::date,
  PRIMARY KEY (id, created_month)
) PARTITION BY RANGE (created_month);
CREATE TABLE matches_default PARTITION OF matches DEFAULT;
CREATE INDEX idx_match_status ON matches(status, queued_at);
CREATE UNIQUE INDEX uq_match_public ON matches(public_id, created_month);

CREATE TABLE match_participants (
  match_id BIGINT NOT NULL,
  created_month DATE NOT NULL,
  side CHAR(1) NOT NULL CHECK (side IN ('A','B')),
  bot_id BIGINT NOT NULL REFERENCES bots(id),
  submission_id BIGINT NOT NULL REFERENCES submissions(id),
  elo_before INT,
  elo_after INT,
  cpu_time_ms INT,
  peak_mem_kb INT,
  fault VARCHAR(24),
  PRIMARY KEY (match_id, side),
  FOREIGN KEY (match_id, created_month) REFERENCES matches(id, created_month)
);
CREATE INDEX idx_mp_bot ON match_participants(bot_id, match_id DESC);

CREATE TABLE replays (
  match_id BIGINT PRIMARY KEY,
  storage_key VARCHAR(255) NOT NULL,
  size_bytes INT NOT NULL,
  bot_a_log_key VARCHAR(255),
  bot_b_log_key VARCHAR(255)
);

CREATE TABLE tournaments (
  id BIGSERIAL PRIMARY KEY,
  season_id BIGINT NOT NULL REFERENCES seasons(id),
  name VARCHAR(100) NOT NULL,
  format VARCHAR(24) NOT NULL DEFAULT 'SINGLE_ELIMINATION',
  bracket_size INT NOT NULL DEFAULT 32,
  best_of SMALLINT NOT NULL DEFAULT 3,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SEEDED','RUNNING','FINISHED')),
  seeded_at TIMESTAMPTZ
);

CREATE TABLE tournament_entries (
  tournament_id BIGINT NOT NULL REFERENCES tournaments(id),
  bot_id BIGINT NOT NULL REFERENCES bots(id),
  submission_id BIGINT NOT NULL REFERENCES submissions(id),
  seed_no INT NOT NULL,
  elo_snapshot INT NOT NULL,
  PRIMARY KEY (tournament_id, bot_id),
  UNIQUE (tournament_id, seed_no)
);

CREATE TABLE bracket_nodes (
  id BIGSERIAL PRIMARY KEY,
  tournament_id BIGINT NOT NULL REFERENCES tournaments(id),
  round_no SMALLINT NOT NULL,
  slot_no SMALLINT NOT NULL,
  bot_a_id BIGINT REFERENCES bots(id),
  bot_b_id BIGINT REFERENCES bots(id),
  winner_bot_id BIGINT REFERENCES bots(id),
  next_node_id BIGINT REFERENCES bracket_nodes(id),
  next_side CHAR(1) CHECK (next_side IN ('A','B')),
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  UNIQUE (tournament_id, round_no, slot_no)
);

CREATE TABLE bracket_games (
  node_id BIGINT NOT NULL REFERENCES bracket_nodes(id),
  game_no SMALLINT NOT NULL,
  match_id BIGINT NOT NULL,
  PRIMARY KEY (node_id, game_no)
);

CREATE TABLE outbox_events (
  id BIGSERIAL PRIMARY KEY,
  topic VARCHAR(64) NOT NULL,
  payload JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  published BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_outbox_unpub ON outbox_events(id) WHERE published = FALSE;
