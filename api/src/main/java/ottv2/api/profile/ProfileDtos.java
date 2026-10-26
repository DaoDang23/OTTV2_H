package ottv2.api.profile;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import ottv2.api.auth.AuthDtos.UserResponse;

public final class ProfileDtos {
    private ProfileDtos() { }

    public record EloHistoryItem(
            long id,
            String opponent,
            int opponentElo,
            String mode,
            boolean userWon,
            int change,
            int newElo,
            String timestamp,
            String replayId,
            boolean isRanked,
            String winner,
            String createdAt
    ) {
        public EloHistoryItem(long id, String opponent, int opponentElo, String mode, boolean userWon, int change, int newElo, String timestamp, String replayId, boolean isRanked, String winner) {
            this(id, opponent, opponentElo, mode, userWon, change, newElo, timestamp, replayId, isRanked, winner, null);
        }

        public EloHistoryItem(long id, String opponent, int opponentElo, String mode, boolean userWon, int change, int newElo, String timestamp, String replayId, boolean isRanked) {
            this(id, opponent, opponentElo, mode, userWon, change, newElo, timestamp, replayId, isRanked, userWon ? "A" : "B", null);
        }

        public EloHistoryItem(long id, String opponent, int opponentElo, String mode, boolean userWon, int change, int newElo, String timestamp, String replayId) {
            this(id, opponent, opponentElo, mode, userWon, change, newElo, timestamp, replayId, true, userWon ? "A" : "B", null);
        }

        public EloHistoryItem(long id, String opponent, int opponentElo, String mode, boolean userWon, int change, int newElo, String timestamp) {
            this(id, opponent, opponentElo, mode, userWon, change, newElo, timestamp, null, true, userWon ? "A" : "B", null);
        }
    }

    public record ProfileResponse(
            UserResponse user,
            int pvpElo,
            int botElo,
            int matchesCount,
            int wins,
            int losses,
            int winRate,
            int botsCount,
            int pvpMatchesCount,
            int pvpWins,
            int pvpLosses,
            int botMatchesCount,
            int botWins,
            int botLosses,
            List<EloHistoryItem> eloHistory,
            int distinctOpponents,
            boolean isProvisional,
            int pvpDistinctOpponents
    ) {
        public ProfileResponse(
                UserResponse user,
                int pvpElo,
                int botElo,
                int matchesCount,
                int wins,
                int losses,
                int winRate,
                int botsCount,
                int pvpMatchesCount,
                int pvpWins,
                int pvpLosses,
                int botMatchesCount,
                int botWins,
                int botLosses,
                List<EloHistoryItem> eloHistory,
                int distinctOpponents,
                boolean isProvisional
        ) {
            this(user, pvpElo, botElo, matchesCount, wins, losses, winRate, botsCount,
                    pvpMatchesCount, pvpWins, pvpLosses, botMatchesCount, botWins, botLosses,
                    eloHistory, distinctOpponents, isProvisional, 0);
        }

        public ProfileResponse(
                UserResponse user,
                int pvpElo,
                int botElo,
                int matchesCount,
                int wins,
                int losses,
                int winRate,
                int botsCount,
                int pvpMatchesCount,
                int pvpWins,
                int pvpLosses,
                int botMatchesCount,
                int botWins,
                int botLosses,
                List<EloHistoryItem> eloHistory
        ) {
            this(user, pvpElo, botElo, matchesCount, wins, losses, winRate, botsCount,
                    pvpMatchesCount, pvpWins, pvpLosses, botMatchesCount, botWins, botLosses, eloHistory, 0, false, 0);
        }

        public ProfileResponse(
                UserResponse user,
                int pvpElo,
                int botElo,
                int matchesCount,
                int wins,
                int losses,
                int winRate,
                int botsCount,
                List<EloHistoryItem> eloHistory
        ) {
            this(user, pvpElo, botElo, matchesCount, wins, losses, winRate, botsCount, 0, 0, 0, 0, 0, 0, eloHistory, 0, false, 0);
        }
    }

    public record RecordMatchRequest(
            @NotBlank String mode,
            @NotBlank String opponent,
            int opponentElo,
            String opponentType,
            boolean userWon,
            @NotBlank String winner,
            String reason,
            int change,
            int newElo,
            boolean isRanked,
            String replayId,
            Object replayData
    ) {
        public RecordMatchRequest(String mode, String opponent, int opponentElo, String opponentType,
                                  boolean userWon, String winner, String reason, int change, int newElo, boolean isRanked, String replayId) {
            this(mode, opponent, opponentElo, opponentType, userWon, winner, reason, change, newElo, isRanked, replayId, null);
        }

        public RecordMatchRequest(String mode, String opponent, int opponentElo, String opponentType,
                                  boolean userWon, String winner, String reason, int change, int newElo, boolean isRanked) {
            this(mode, opponent, opponentElo, opponentType, userWon, winner, reason, change, newElo, isRanked, null, null);
        }
    }

    public record UpdateProfileRequest(String studentCode, String displayName) { }
}
