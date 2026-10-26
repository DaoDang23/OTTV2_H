package ottv2.api.match;

public record MatchJob(String type, long matchId, String publicId, String createdMonth, long seed,
                       MapConfiguration mapConfig, ParticipantJob sideA, ParticipantJob sideB) {
    public record MapConfiguration(int obstacles, int traps, int speeds, int mutations) { }
    public record ParticipantJob(long botId, String botName, long ownerId, long submissionId,
                                 String language, String artifactKey, String sourceSha256) { }
}
