package ottv2.api.tournament.strategy;

import ottv2.api.tournament.TournamentDtos.*;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class SwissStrategy implements TournamentFormatStrategy {

    @Override
    public TournamentFormat getFormat() {
        return TournamentFormat.SWISS;
    }

    @Override
    public List<MatchPlan> generateInitialSchedule(long tournamentId, List<TeamSeed> teams, RulesConfig rules) {
        int k = teams.size();
        if (k < 2) {
            throw new IllegalArgumentException("Swiss system requires at least 2 teams; found " + k);
        }

        // Sort teams by seed ascending
        List<TeamSeed> sorted = new ArrayList<>(teams);
        sorted.sort(Comparator.comparingInt(t -> t.seed() > 0 ? t.seed() : Integer.MAX_VALUE));

        List<MatchPlan> plans = new ArrayList<>();
        int matchNo = 1;

        // If odd number of teams, lowest seed gets a BYE
        TeamSeed byeTeam = null;
        if (k % 2 != 0) {
            byeTeam = sorted.remove(sorted.size() - 1);
        }

        // Pair remaining even teams: top half vs bottom half
        int half = sorted.size() / 2;
        for (int i = 0; i < half; i++) {
            TeamSeed t1 = sorted.get(i);
            TeamSeed t2 = sorted.get(i + half);
            plans.add(new MatchPlan(1, matchNo++, "SWISS_ROUND_1", t1.teamId(), t2.teamId(), null, TournamentMatchStatus.READY));
        }

        if (byeTeam != null) {
            plans.add(new MatchPlan(1, matchNo++, "SWISS_ROUND_1", byeTeam.teamId(), null, byeTeam.teamId(), TournamentMatchStatus.BYE));
        }

        return Collections.unmodifiableList(plans);
    }

    @Override
    public boolean requiresDynamicNextRound() {
        return true;
    }

    @Override
    public List<MatchPlan> generateNextRound(long tournamentId, int nextRoundNo, List<TeamSeed> teams,
                                            List<MatchRecord> completedMatches, RulesConfig rules) {
        int totalSwissRounds = rules.swissRoundsCount(teams.size());
        if (nextRoundNo > totalSwissRounds) {
            return List.of();
        }

        // Track previous matchups and BYEs
        Set<String> previousPairs = new HashSet<>();
        Set<Long> teamsWithBye = new HashSet<>();

        for (MatchRecord m : completedMatches) {
            if (m.teamAId() != null && m.teamBId() != null) {
                previousPairs.add(pairKey(m.teamAId(), m.teamBId()));
            } else if (m.status() == TournamentMatchStatus.BYE && m.teamAId() != null) {
                teamsWithBye.add(m.teamAId());
            }
        }

        // Sort teams by score descending, buchholz descending, seed ascending
        List<TeamSeed> available = new ArrayList<>(teams);
        available.sort(Comparator
                .comparingDouble(TeamSeed::score).reversed()
                .thenComparing(Comparator.comparingDouble(TeamSeed::buchholzScore).reversed())
                .thenComparingInt(t -> t.seed() > 0 ? t.seed() : Integer.MAX_VALUE));

        List<MatchPlan> plans = new ArrayList<>();
        int matchNo = 1;

        // If odd number of teams, assign BYE to lowest-scoring team that hasn't received a BYE
        if (available.size() % 2 != 0) {
            int byeIndex = -1;
            // Search from bottom up
            for (int i = available.size() - 1; i >= 0; i--) {
                if (!teamsWithBye.contains(available.get(i).teamId())) {
                    byeIndex = i;
                    break;
                }
            }
            if (byeIndex == -1) {
                byeIndex = available.size() - 1; // Fallback: last team
            }
            TeamSeed byeTeam = available.remove(byeIndex);
            plans.add(new MatchPlan(nextRoundNo, matchNo++, "SWISS_ROUND_" + nextRoundNo,
                    byeTeam.teamId(), null, byeTeam.teamId(), TournamentMatchStatus.BYE));
        }

        // Pair remaining teams using backtracking without rematches
        List<TeamPair> pairs = new ArrayList<>();
        boolean paired = pairSwissTeams(available, previousPairs, pairs);
        if (!paired) {
            // Fallback greedy pairing if perfect rematch-avoidance is mathematically blocked
            pairs.clear();
            fallbackGreedyPairing(available, previousPairs, pairs);
        }

        for (TeamPair pair : pairs) {
            plans.add(new MatchPlan(nextRoundNo, matchNo++, "SWISS_ROUND_" + nextRoundNo,
                    pair.t1().teamId(), pair.t2().teamId(), null, TournamentMatchStatus.READY));
        }

        return Collections.unmodifiableList(plans);
    }

    @Override
    public boolean isTournamentComplete(int currentRound, List<TeamSeed> teams,
                                       List<MatchRecord> allMatches, RulesConfig rules) {
        int totalSwissRounds = rules.swissRoundsCount(teams.size());
        if (currentRound < totalSwissRounds) return false;

        // All matches in round R must be completed
        return allMatches.stream()
                .filter(m -> m.roundNo() == totalSwissRounds)
                .allMatch(m -> m.status() == TournamentMatchStatus.FINISHED ||
                               m.status() == TournamentMatchStatus.BYE ||
                               m.status() == TournamentMatchStatus.CANCELLED);
    }

    private boolean pairSwissTeams(List<TeamSeed> unassigned, Set<String> previousPairs, List<TeamPair> outPairs) {
        if (unassigned.isEmpty()) return true;

        TeamSeed first = unassigned.get(0);
        for (int i = 1; i < unassigned.size(); i++) {
            TeamSeed opponent = unassigned.get(i);
            String key = pairKey(first.teamId(), opponent.teamId());
            if (!previousPairs.contains(key)) {
                outPairs.add(new TeamPair(first, opponent));
                List<TeamSeed> remaining = new ArrayList<>(unassigned);
                remaining.remove(i);
                remaining.remove(0);

                if (pairSwissTeams(remaining, previousPairs, outPairs)) {
                    return true;
                }
                outPairs.remove(outPairs.size() - 1);
            }
        }
        return false;
    }

    private void fallbackGreedyPairing(List<TeamSeed> unassigned, Set<String> previousPairs, List<TeamPair> outPairs) {
        List<TeamSeed> pool = new ArrayList<>(unassigned);
        while (pool.size() >= 2) {
            TeamSeed first = pool.remove(0);
            int bestIdx = 0;
            boolean foundFresh = false;
            for (int i = 0; i < pool.size(); i++) {
                if (!previousPairs.contains(pairKey(first.teamId(), pool.get(i).teamId()))) {
                    bestIdx = i;
                    foundFresh = true;
                    break;
                }
            }
            TeamSeed opponent = pool.remove(bestIdx);
            outPairs.add(new TeamPair(first, opponent));
        }
    }

    public static String pairKey(long idA, long idB) {
        return Math.min(idA, idB) + "_" + Math.max(idA, idB);
    }

    private record TeamPair(TeamSeed t1, TeamSeed t2) { }
}
