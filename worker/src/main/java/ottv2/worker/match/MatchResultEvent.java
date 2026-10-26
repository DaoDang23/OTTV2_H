package ottv2.worker.match;

import ottv2.worker.match.MatchExecutionService.MatchOutcome;

public record MatchResultEvent(String type, long matchId, String createdMonth, String workerId,
                               String winnerSide, String endReason, Integer totalTurns,
                               String faultA, String faultB, Integer cpuTimeMsA, Integer cpuTimeMsB,
                               Integer peakMemKbA, Integer peakMemKbB, String replayKey,
                               Integer replaySizeBytes, String botALogKey, String botBLogKey, String error,
                               String replayData) {

    public MatchResultEvent(String type, long matchId, String createdMonth, String workerId,
                            String winnerSide, String endReason, Integer totalTurns,
                            String faultA, String faultB, Integer cpuTimeMsA, Integer cpuTimeMsB,
                            Integer peakMemKbA, Integer peakMemKbB, String replayKey,
                            Integer replaySizeBytes, String botALogKey, String botBLogKey, String error) {
        this(type, matchId, createdMonth, workerId, winnerSide, endReason, totalTurns,
             faultA, faultB, cpuTimeMsA, cpuTimeMsB, peakMemKbA, peakMemKbB,
             replayKey, replaySizeBytes, botALogKey, botBLogKey, error, null);
    }

    public static MatchResultEvent started(MatchJob job, String workerId) {
        return new MatchResultEvent("MATCH_STARTED", job.matchId(), job.createdMonth(), workerId,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static MatchResultEvent finished(MatchOutcome outcome, String workerId) {
        return new MatchResultEvent("MATCH_FINISHED", outcome.matchId(), outcome.createdMonth(), workerId,
                outcome.winnerSide(), outcome.endReason(), outcome.totalTurns(), outcome.faultA(), outcome.faultB(),
                null, null, null, null, outcome.replayKey(), outcome.replaySizeBytes(),
                outcome.botALogKey(), outcome.botBLogKey(), null, outcome.replayJson());
    }

    public static MatchResultEvent failed(MatchJob job, String workerId, Exception failure) {
        String error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        if (error.length() > 2048) error = error.substring(0, 2048);
        return new MatchResultEvent("MATCH_FAILED", job.matchId(), job.createdMonth(), workerId,
                null, null, null, null, null, null, null, null, null, null, null, null, null, error, null);
    }
}
