package ottv2.worker.compile;

public record CompileResult(long submissionId, String status, String artifactKey, String compileLog) { }
