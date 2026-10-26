-- V16: Add ON DELETE CASCADE to legacy tournament tables to ensure safe deletions
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints 
        WHERE constraint_name = 'tournament_entries_tournament_id_fkey'
    ) THEN
        ALTER TABLE tournament_entries DROP CONSTRAINT tournament_entries_tournament_id_fkey;
        ALTER TABLE tournament_entries ADD CONSTRAINT tournament_entries_tournament_id_fkey 
            FOREIGN KEY (tournament_id) REFERENCES tournaments(id) ON DELETE CASCADE;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints 
        WHERE constraint_name = 'bracket_nodes_tournament_id_fkey'
    ) THEN
        ALTER TABLE bracket_nodes DROP CONSTRAINT bracket_nodes_tournament_id_fkey;
        ALTER TABLE bracket_nodes ADD CONSTRAINT bracket_nodes_tournament_id_fkey 
            FOREIGN KEY (tournament_id) REFERENCES tournaments(id) ON DELETE CASCADE;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints 
        WHERE constraint_name = 'bracket_games_node_id_fkey'
    ) THEN
        ALTER TABLE bracket_games DROP CONSTRAINT bracket_games_node_id_fkey;
        ALTER TABLE bracket_games ADD CONSTRAINT bracket_games_node_id_fkey 
            FOREIGN KEY (node_id) REFERENCES bracket_nodes(id) ON DELETE CASCADE;
    END IF;
END $$;
