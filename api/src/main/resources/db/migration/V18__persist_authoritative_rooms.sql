CREATE TABLE IF NOT EXISTS room_sessions (
    code VARCHAR(6) PRIMARY KEY,
    host_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    host_identity VARCHAR(320) NOT NULL,
    host_name VARCHAR(100) NOT NULL,
    guest_user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    guest_identity VARCHAR(320),
    guest_name VARCHAR(100),
    status VARCHAR(16) NOT NULL CHECK (status IN ('WAITING', 'PLAYING', 'FINISHED')),
    time_control JSONB,
    game_state JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    joined_at TIMESTAMPTZ,
    last_active_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    host_ready BOOLEAN NOT NULL DEFAULT TRUE,
    guest_ready BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK ((guest_user_id IS NULL) = (guest_identity IS NULL)),
    CHECK ((guest_user_id IS NULL) = (guest_name IS NULL))
);

CREATE INDEX IF NOT EXISTS idx_room_sessions_status_activity ON room_sessions(status, last_active_at);
