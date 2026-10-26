package ottv2.api.tournament.strategy;

import ottv2.api.tournament.TournamentDtos.*;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class RoundRobinStrategy implements TournamentFormatStrategy {

    @Override
    public TournamentFormat getFormat() {
        return TournamentFormat.ROUND_ROBIN;
    }

    @Override
    public List<MatchPlan> generateInitialSchedule(long tournamentId, List<TeamSeed> teams, RulesConfig rules) {
        int k = teams.size();
        if (k < 2) {
            throw new IllegalArgumentException("Round Robin requires at least 2 teams; found " + k);
        }

        int m = (k % 2 == 0) ? k : k + 1;
        TeamSeed[] list = new TeamSeed[m];
        for (int i = 0; i < k; i++) {
            list[i] = teams.get(i);
        }
        if (m > k) {
            list[m - 1] = null; // Dummy team representing BYE
        }

        int totalRounds = m - 1;
        int matchesPerRound = m / 2;

        List<MatchPlan> plans = new ArrayList<>();

        for (int r = 1; r <= totalRounds; r++) {
            int matchInRound = 1;
            for (int i = 0; i < matchesPerRound; i++) {
                TeamSeed t1 = list[i];
                TeamSeed t2 = list[m - 1 - i];

                // Alternate home/away sides for fairness
                if ((r + i) % 2 == 1) {
                    TeamSeed temp = t1;
                    t1 = t2;
                    t2 = temp;
                }

                if (t1 != null && t2 != null) {
                    // Normal matchup
                    TournamentMatchStatus status = (r == 1) ? TournamentMatchStatus.READY : TournamentMatchStatus.SCHEDULED;
                    plans.add(new MatchPlan(r, matchInRound++, "ROUND_ROBIN", t1.teamId(), t2.teamId(), null, status));
                } else {
                    // One team has a BYE
                    TeamSeed realTeam = (t1 != null) ? t1 : t2;
                    plans.add(new MatchPlan(r, matchInRound++, "ROUND_ROBIN", realTeam.teamId(), null, realTeam.teamId(), TournamentMatchStatus.BYE));
                }
            }

            // Rotate list elements 1..m-1 clockwise, keeping index 0 fixed
            TeamSeed last = list[m - 1];
            System.arraycopy(list, 1, list, 2, m - 2);
            list[1] = last;
        }

        return Collections.unmodifiableList(plans);
    }

    @Override
    public boolean requiresDynamicNextRound() {
        return false;
    }

    @Override
    public List<MatchPlan> generateNextRound(long tournamentId, int nextRoundNo, List<TeamSeed> teams,
                                            List<MatchRecord> completedMatches, RulesConfig rules) {
        return List.of();
    }

    @Override
    public boolean isTournamentComplete(int currentRound, List<TeamSeed> teams,
                                       List<MatchRecord> allMatches, RulesConfig rules) {
        if (allMatches.isEmpty()) return false;
        return allMatches.stream().allMatch(m ->
                m.status() == TournamentMatchStatus.FINISHED ||
                m.status() == TournamentMatchStatus.BYE ||
                m.status() == TournamentMatchStatus.CANCELLED
        );
    }
}
