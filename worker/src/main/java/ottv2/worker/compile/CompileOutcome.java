package ottv2.worker.compile;

public record CompileOutcome(CompileResult result, byte[] artifactBytes) { }
