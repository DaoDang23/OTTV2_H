package ottv2.api.submission;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class SubmissionDtos {
    private SubmissionDtos() { }

    public enum Language { JAVA, CPP, PYTHON }

    public record CreateSubmissionRequest(@NotNull Language language,
                                          @NotBlank @Size(max = 262144) String source) { }

    public record SubmissionResponse(long id, long botId, int versionNo, Language language,
                                     String status, String sourceSha256, String compileLog,
                                     Instant createdAt) { }

    public record CompileJob(long submissionId, long botId, int versionNo, String language,
                             String sourceKey, String sourceSha256) { }
}
