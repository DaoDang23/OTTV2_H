package ottv2.api.bot;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BotDtos {
    private BotDtos() { }
    public record CreateBotRequest(
            @NotBlank(message = "Tên bot không được để trống")
            @Size(min = 3, max = 20, message = "Tên bot phải từ 3 đến 20 ký tự")
            @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "Tên bot chỉ chứa chữ cái, số, dấu gạch dưới hoặc gạch ngang")
            String name
    ) { }
    public record SetLadderActiveRequest(Boolean active) { }
    public record BotResponse(
            long id,
            String name,
            boolean ladderEnabled,
            int elo,
            int games,
            int wins,
            int losses,
            int draws,
            double winRate,
            boolean provisional
    ) {
        public BotResponse(long id, String name, boolean ladderEnabled) {
            this(id, name, ladderEnabled, 1200, 0, 0, 0, 0, 0.0, true);
        }

        public BotResponse(long id, String name, boolean ladderEnabled, int elo) {
            this(id, name, ladderEnabled, elo, 0, 0, 0, 0, 0.0, true);
        }
    }

    public record BotMatchSummary(
            UUID matchPublicId,
            String matchType,
            String status,
            String result,
            String opponentBotName,
            String opponentOwnerName,
            Integer opponentElo,
            Integer eloBefore,
            Integer eloAfter,
            Instant playedAt
    ) { }

    public record BotDetailResponse(
            long id,
            String name,
            long ownerId,
            String ownerName,
            int elo,
            String language,
            int versionNo,
            String sourceSha256,
            int games,
            int wins,
            int losses,
            int draws,
            double winRate,
            int distinctOpponents,
            boolean provisional,
            List<BotMatchSummary> recentMatches
    ) { }
}
