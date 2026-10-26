ALTER TABLE bracket_games
  ADD CONSTRAINT ck_bracket_game_no CHECK (game_no BETWEEN 1 AND 3);
CREATE UNIQUE INDEX uq_bracket_game_match ON bracket_games(match_id);
CREATE INDEX idx_bracket_tournament_round ON bracket_nodes(tournament_id, round_no, slot_no);
