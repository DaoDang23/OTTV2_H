package ottv2.api.submission;

import jakarta.validation.Valid;
import java.util.List;
import ottv2.api.security.ApiPrincipal;
import ottv2.api.submission.SubmissionDtos.CreateSubmissionRequest;
import ottv2.api.submission.SubmissionDtos.SubmissionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bots/{botId}/submissions")
public class SubmissionController {
    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) { this.submissionService = submissionService; }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SubmissionResponse submit(@AuthenticationPrincipal ApiPrincipal principal,
                                     @PathVariable long botId,
                                     @Valid @RequestBody CreateSubmissionRequest request) {
        return submissionService.submit(principal.id(), botId, request);
    }

    @GetMapping
    public List<SubmissionResponse> list(@AuthenticationPrincipal ApiPrincipal principal,
                                         @PathVariable long botId) {
        return submissionService.list(principal.id(), botId);
    }

    @GetMapping("/{submissionId}")
    public SubmissionResponse get(@AuthenticationPrincipal ApiPrincipal principal,
                                  @PathVariable long botId, @PathVariable long submissionId) {
        return submissionService.get(principal.id(), botId, submissionId);
    }
}
