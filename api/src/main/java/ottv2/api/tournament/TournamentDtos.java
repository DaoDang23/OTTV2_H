package ottv2.api.tournament;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class TournamentDtos {
    private TournamentDtos() { }

    public enum TournamentFormat {
        SINGLE_ELIMINATION,
        ROUND_ROBIN,
        SWISS
    }

    public enum TournamentStatus {
        DRAFT,
        REGISTRATION_OPEN,
        REGISTRATION_CLOSED,
        CHECK_IN,
        RUNNING,
        COMPLETED,
        SUSPENDED,
        CANCELLED;

        public boolean canTransitionTo(TournamentStatus next) {
            if (this == next) return true;
            return switch (this) {
                case DRAFT -> next == REGISTRATION_OPEN || next == CANCELLED;
                case REGISTRATION_OPEN -> next == REGISTRATION_CLOSED || next == CANCELLED;
                case REGISTRATION_CLOSED -> next == CHECK_IN || next == REGISTRATION_OPEN || next == CANCELLED;
                case CHECK_IN -> next == RUNNING || next == REGISTRATION_CLOSED || next == CANCELLED;
                case RUNNING -> next == COMPLETED || next == SUSPENDED || next == CANCELLED;
                case SUSPENDED -> next == RUNNING || next == CANCELLED;
                case COMPLETED, CANCELLED -> false;
            };
        }
    }

    public enum TeamStatus {
        REGISTERED,
        VERIFIED,
        CHECKED_IN,
        DISQUALIFIED,
        ELIMINATED
    }

    public enum MemberRole {
        CAPTAIN,
        MEMBER
    }

    public enum TournamentMatchStatus {
        SCHEDULED,
        READY,
        RUNNING,
        FINISHED,
        BYE,
        ERROR,
        CANCELLED
    }

    public record RulesConfig(
            int initialTimeSeconds,
            int incrementSeconds,
            int timeoutSeconds,
            int memoryLimitMb,
            String tieBreakRules,
            Integer pointsPerWin,
            Integer pointsPerDraw,
            Integer pointsPerLoss,
            Integer swissRounds
    ) {
        public RulesConfig(int initialTimeSeconds, int incrementSeconds, int timeoutSeconds, int memoryLimitMb, String tieBreakRules) {
            this(initialTimeSeconds, incrementSeconds, timeoutSeconds, memoryLimitMb, tieBreakRules, 3, 1, 0, null);
        }

        public int winPts() { return pointsPerWin != null ? pointsPerWin : 3; }
        public int drawPts() { return pointsPerDraw != null ? pointsPerDraw : 1; }
        public int lossPts() { return pointsPerLoss != null ? pointsPerLoss : 0; }

        public int swissRoundsCount(int teamCount) {
            if (swissRounds != null && swissRounds > 0) return swissRounds;
            return Math.max(3, (int) Math.ceil(Math.log(teamCount) / Math.log(2)));
        }

        public static RulesConfig defaultBlitz() {
            return new RulesConfig(180, 2, 3, 256, "BUCHHOLZ,DIRECT_ENCOUNTER", 3, 1, 0, 3);
        }
    }

    public record CreateTournamentRequest(
            @NotBlank @Size(max = 255) String title,
            @Size(max = 120) String slug,
            String description,
            TournamentFormat format,
            @Min(2) @Max(128) Integer maxTeams,
            @Min(1) @Max(10) Integer teamSize,
            RulesConfig rulesConfig
    ) { }

    public record UpdateTournamentRequest(
            @NotBlank @Size(max = 255) String title,
            String description,
            @Min(2) @Max(128) Integer maxTeams,
            @Min(1) @Max(10) Integer teamSize,
            RulesConfig rulesConfig
    ) { }

    public record ChangeStatusRequest(
            @NotNull TournamentStatus status,
            String reason
    ) { }

    public record RegisterTeamRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 32) String teamCode,
            @NotNull Long botId,
            @NotNull Long submissionId,
            List<Long> memberUserIds
    ) { }

    public record CheckInTeamRequest(
            Long submissionId
    ) { }

    public record DisqualifyTeamRequest(
            @NotBlank String reason
    ) { }

    public record ActionWithReasonRequest(
            @NotBlank String reason
    ) { }

    public record ResolveDisputeRequest(
            @NotNull Long winnerTeamId,
            Integer scoreA,
            Integer scoreB,
            @NotBlank String reason
    ) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TournamentResponse(
            long id,
            String title,
            String slug,
            String description,
            String format,
            String status,
            int maxTeams,
            int teamSize,
            RulesConfig rulesConfig,
            Long createdBy,
            Instant createdAt,
            Instant updatedAt,
            int teamCount
    ) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TeamResponse(
            long id,
            long tournamentId,
            String name,
            String teamCode,
            long captainId,
            String captainName,
            Long botId,
            String botName,
            Long submissionId,
            String sourceSha256,
            Integer versionNo,
            String status,
            Integer seed,
            double score,
            double buchholzScore,
            Instant createdAt,
            List<MemberResponse> members
    ) { }

    public record MemberResponse(
            long userId,
            String studentCode,
            String displayName,
            String role,
            Instant joinedAt
    ) { }

    public record AuditLogResponse(
            long id,
            long tournamentId,
            Long operatorId,
            String action,
            String details,
            Instant createdAt
    ) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TournamentMatchResponse(
            long id,
            long tournamentId,
            int roundNo,
            int matchNo,
            String stage,
            Long matchId,
            Long teamAId,
            String teamAName,
            Long teamBId,
            String teamBName,
            Long winnerTeamId,
            String winnerTeamName,
            String status,
            int scoreA,
            int scoreB,
            Instant createdAt,
            Instant updatedAt
    ) { }

    public record StandingRow(
            long teamId,
            String teamName,
            int rank,
            int played,
            int wins,
            int draws,
            int losses,
            double score,
            double buchholzScore,
            String status
    ) { }

    public record BracketViewResponse(
            TournamentResponse tournament,
            List<TournamentMatchResponse> matches,
            List<StandingRow> standings
    ) { }

    public record TeamSeed(
            long teamId,
            String teamName,
            int seed,
            double score,
            double buchholzScore
    ) { }

    public record MatchPlan(
            int roundNo,
            int matchNo,
            String stage,
            Long teamAId,
            Long teamBId,
            Long winnerTeamId,
            TournamentMatchStatus status
    ) { }

    public record MatchRecord(
            long id,
            int roundNo,
            int matchNo,
            String stage,
            Long teamAId,
            Long teamBId,
            Long winnerTeamId,
            TournamentMatchStatus status,
            int scoreA,
            int scoreB
    ) { }

    public record UserTournamentParticipation(
            long tournamentId,
            String tournamentTitle,
            String tournamentFormat,
            String tournamentStatus,
            long teamId,
            String teamName,
            String teamStatus,
            double score,
            double buchholzScore,
            Integer seed,
            Long botId,
            String botName,
            Integer versionNo
    ) { }
}

