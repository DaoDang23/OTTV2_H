package ottv2.api.match;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public final class MatchDtos {
    private MatchDtos() { }

    public record CreateMatchRequest(@NotNull Long submissionAId, @NotNull Long submissionBId) { }
    public record MatchResponse(UUID id, String status, Long seed, String winnerSide,
                                String endReason, Integer totalTurns, Instant queuedAt,
                                Instant finishedAt, Participant sideA, Participant sideB) { }
    public record Participant(long botId, String botName, long submissionId, String fault, String sha256, Integer versionNo,
                              Long ownerId, Integer eloBefore, Integer eloAfter) {
        public Participant(long botId, String botName, long submissionId, String fault, String sha256, Integer versionNo) {
            this(botId, botName, submissionId, fault, sha256, versionNo, null, null, null);
        }

        public Participant(long botId, String botName, long submissionId, String fault) {
            this(botId, botName, submissionId, fault, null, null, null, null, null);
        }
    }
    public record PlayableSubmission(long id, long botId, String botName, int versionNo,
                                     String language, String status) { }

    public record FinishPvpMatchRequest(
            String winnerSide,
            String reason,
            Object replayData,
            Long userId
    ) {
        public FinishPvpMatchRequest(String winnerSide, String reason, Object replayData) {
            this(winnerSide, reason, replayData, null);
        }
    }

    public record PvpMatchFinishResponse(
            String matchPublicId,
            String status,
            String winnerSide,
            String reason,
            int eloChange,
            int newElo,
            boolean isRanked,
            int oldElo,
            String opponentName,
            int opponentElo,
            int opponentEloChange,
            int opponentNewElo
    ) {
        public PvpMatchFinishResponse(
                String matchPublicId,
                String status,
                String winnerSide,
                String reason,
                int eloChange,
                int newElo,
                boolean isRanked
        ) {
            this(matchPublicId, status, winnerSide, reason, eloChange, newElo, isRanked, newElo - eloChange, null, 1200, 0, 1200);
        }
    }
}
