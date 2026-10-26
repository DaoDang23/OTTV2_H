CREATE TABLE ladder_series (
                               id BIGSERIAL PRIMARY KEY,
                               season_id BIGINT NOT NULL REFERENCES seasons(id),
                               bot_a_id BIGINT NOT NULL REFERENCES bots(id),
                               bot_b_id BIGINT NOT NULL REFERENCES bots(id),
                               submission_a_id BIGINT NOT NULL REFERENCES submissions(id),
                               submission_b_id BIGINT NOT NULL REFERENCES submissions(id),
                               seed BIGINT NOT NULL,
                               score_a NUMERIC(2,1) NOT NULL DEFAULT 0,
                               games_completed SMALLINT NOT NULL DEFAULT 0 CHECK (games_completed BETWEEN 0 AND 2),
                               finished_at TIMESTAMPTZ,
                               created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                               CHECK (bot_a_id <> bot_b_id)
);

ALTER TABLE matches ADD COLUMN ladder_series_id BIGINT REFERENCES ladder_series(id);
ALTER TABLE matches ADD COLUMN series_game_no SMALLINT CHECK (series_game_no IN (1, 2));

-- Sửa lại dòng dưới đây: Thêm created_month vào trong ngoặc
CREATE UNIQUE INDEX uq_ladder_series_game ON matches(ladder_series_id, series_game_no, created_month)
    WHERE ladder_series_id IS NOT NULL;

CREATE INDEX idx_ladder_series_season_created ON ladder_series(season_id, created_at DESC);