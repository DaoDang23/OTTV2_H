package ottv2.worker.compile;

public record CompileJob(long submissionId, long botId, int versionNo, String language,
                         String sourceKey, String sourceSha256) { }
