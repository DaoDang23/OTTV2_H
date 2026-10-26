package ottv2.api.tournament.strategy;

import ottv2.api.tournament.TournamentDtos.*;

import java.util.List;

public interface TournamentFormatStrategy {
    TournamentFormat getFormat();

    /**
     * Generates initial match plans when tournament moves to RUNNING.
     */
    List<MatchPlan> generateInitialSchedule(long tournamentId, List<TeamSeed> teams, RulesConfig rules);

    /**
     * Indicates if next rounds must be generated dynamically round-by-round (e.g. Swiss).
     */
    boolean requiresDynamicNextRound();

    /**
     * Generates pairings for next round (e.g. Swiss).
     */
    List<MatchPlan> generateNextRound(long tournamentId, int nextRoundNo, List<TeamSeed> teams,
                                      List<MatchRecord> completedMatches, RulesConfig rules);

    /**
     * Checks whether all conditions for tournament completion are fulfilled.
     */
    boolean isTournamentComplete(int currentRound, List<TeamSeed> teams,
                                List<MatchRecord> allMatches, RulesConfig rules);
}
