package ottv2.api.tournament.strategy;

import ottv2.api.tournament.TournamentDtos.*;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class SingleEliminationStrategy implements TournamentFormatStrategy {

    @Override
    public TournamentFormat getFormat() {
        return TournamentFormat.SINGLE_ELIMINATION;
    }

    @Override
    public List<MatchPlan> generateInitialSchedule(long tournamentId, List<TeamSeed> teams, RulesConfig rules) {
        int k = teams.size();
        if (k < 2) {
            throw new IllegalArgumentException("Single Elimination requires at least 2 teams; found " + k);
        }

        // Compute power of 2 bracket size N >= k
        int n = 2;
        int totalRounds = 1;
        while (n < k) {
            n *= 2;
            totalRounds++;
        }

        // Map seeds (1-indexed) to teams
        Map<Integer, TeamSeed> seedToTeam = new HashMap<>();
        for (int i = 0; i < k; i++) {
            TeamSeed t = teams.get(i);
            int seed = t.seed() > 0 ? t.seed() : (i + 1);
            seedToTeam.put(seed, t);
        }

        int[] seedOrder = generateSeedOrder(n);
        int round1MatchCount = n / 2;

        // Create container for plans
        Map<String, MatchPlanMutable> roundPlans = new LinkedHashMap<>();

        // 1. Initialize all rounds
        for (int r = 1; r <= totalRounds; r++) {
            int matchesInRound = n >> r;
            String stage = getStageName(r, totalRounds);
            for (int m = 1; m <= matchesInRound; m++) {
                roundPlans.put(key(r, m), new MatchPlanMutable(r, m, stage));
            }
        }

        // 2. Populate Round 1 pairings
        for (int m = 1; m <= round1MatchCount; m++) {
            int seedA = seedOrder[2 * (m - 1)];
            int seedB = seedOrder[2 * (m - 1) + 1];

            TeamSeed teamA = seedToTeam.get(seedA);
            TeamSeed teamB = seedToTeam.get(seedB);

            MatchPlanMutable plan = roundPlans.get(key(1, m));

            if (teamA != null && teamB != null) {
                // Both teams present
                plan.teamAId = teamA.teamId();
                plan.teamBId = teamB.teamId();
                plan.status = TournamentMatchStatus.READY;
            } else if (teamA != null) {
                // Team A receives a BYE
                plan.teamAId = teamA.teamId();
                plan.teamBId = null;
                plan.winnerTeamId = teamA.teamId();
                plan.status = TournamentMatchStatus.BYE;
            } else if (teamB != null) {
                // Team B receives a BYE
                plan.teamAId = teamB.teamId();
                plan.teamBId = null;
                plan.winnerTeamId = teamB.teamId();
                plan.status = TournamentMatchStatus.BYE;
            }
        }

        // 3. Immediately advance Round 1 BYE winners into Round 2
        if (totalRounds >= 2) {
            for (int m = 1; m <= round1MatchCount; m++) {
                MatchPlanMutable r1Plan = roundPlans.get(key(1, m));
                if (r1Plan.status == TournamentMatchStatus.BYE && r1Plan.winnerTeamId != null) {
                    int nextMatch = (m + 1) / 2;
                    MatchPlanMutable r2Plan = roundPlans.get(key(2, nextMatch));
                    if (r2Plan != null) {
                        if (m % 2 == 1) {
                            r2Plan.teamAId = r1Plan.winnerTeamId;
                        } else {
                            r2Plan.teamBId = r1Plan.winnerTeamId;
                        }
                        if (r2Plan.teamAId != null && r2Plan.teamBId != null) {
                            r2Plan.status = TournamentMatchStatus.READY;
                        }
                    }
                }
            }
        }

        // Convert to immutable MatchPlan list
        List<MatchPlan> result = new ArrayList<>();
        for (MatchPlanMutable mutable : roundPlans.values()) {
            result.add(new MatchPlan(
                    mutable.roundNo,
                    mutable.matchNo,
                    mutable.stage,
                    mutable.teamAId,
                    mutable.teamBId,
                    mutable.winnerTeamId,
                    mutable.status
            ));
        }

        return Collections.unmodifiableList(result);
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
        // Tournament is complete when the Final match is FINISHED or BYE
        int maxRound = allMatches.stream().mapToInt(MatchRecord::roundNo).max().orElse(1);
        return allMatches.stream()
                .filter(m -> m.roundNo() == maxRound && m.matchNo() == 1)
                .anyMatch(m -> m.status() == TournamentMatchStatus.FINISHED || m.status() == TournamentMatchStatus.BYE);
    }

    public static int[] generateSeedOrder(int n) {
        if (n <= 2) return new int[]{1, 2};
        int[] prev = generateSeedOrder(n / 2);
        int[] result = new int[n];
        for (int i = 0; i < prev.length; i++) {
            result[2 * i] = prev[i];
            result[2 * i + 1] = n + 1 - prev[i];
        }
        return result;
    }

    private static String getStageName(int roundNo, int totalRounds) {
        if (roundNo == totalRounds) return "FINAL";
        if (roundNo == totalRounds - 1) return "SEMI_FINALS";
        if (roundNo == totalRounds - 2) return "QUARTER_FINALS";
        return "ROUND_" + roundNo;
    }

    private static String key(int r, int m) {
        return r + ":" + m;
    }

    private static class MatchPlanMutable {
        final int roundNo;
        final int matchNo;
        final String stage;
        Long teamAId;
        Long teamBId;
        Long winnerTeamId;
        TournamentMatchStatus status = TournamentMatchStatus.SCHEDULED;

        MatchPlanMutable(int roundNo, int matchNo, String stage) {
            this.roundNo = roundNo;
            this.matchNo = matchNo;
            this.stage = stage;
        }
    }
}
