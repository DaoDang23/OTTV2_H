package ottv2.api.match;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import ottv2.api.match.MatchDtos.CreateMatchRequest;
import ottv2.api.match.MatchDtos.MatchResponse;
import ottv2.api.match.MatchDtos.PlayableSubmission;
import ottv2.api.match.MatchDtos.FinishPvpMatchRequest;
import ottv2.api.match.MatchDtos.PvpMatchFinishResponse;
import ottv2.api.security.ApiPrincipal;
import ottv2.api.storage.SourceObjectStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/matches")
public class MatchController {
    private final MatchService matches;
    private final SourceObjectStore objects;

    public MatchController(MatchService matches, SourceObjectStore objects) {
        this.matches = matches;
        this.objects = objects;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MatchResponse create(@AuthenticationPrincipal ApiPrincipal principal,
                                @Valid @RequestBody CreateMatchRequest request) {
        if (principal == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập!");
        }
        return matches.create(principal.id(), request);
    }

    @PostMapping("/{matchIdentifier}/finish")
    public PvpMatchFinishResponse finishPvpMatch(
            @AuthenticationPrincipal ApiPrincipal principal,
            @PathVariable String matchIdentifier,
            @RequestBody FinishPvpMatchRequest request) {
        long userId = principal != null ? principal.id() : -1L;
        if (userId <= 0 && request != null && request.userId() != null) {
            userId = request.userId();
        }
        if (userId <= 0) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập!");
        }
        return matches.finishPvpMatch(userId, matchIdentifier, request);
    }

    @GetMapping
    public List<MatchResponse> list(
            @AuthenticationPrincipal ApiPrincipal principal,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Long userId) {
        if (principal == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để xem lịch sử trận đấu!");
        }
        // Chống rò rỉ dữ liệu chéo: Nếu truyền tham số userId hoặc username khác với user đang đăng nhập, lập tức chặn với 403 Forbidden
        if (userId != null && userId != principal.id()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN, "Không được phép xem lịch sử của người dùng khác!");
        }
        if (username != null && !principal.matchesUsername(username)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN, "Không được phép xem lịch sử của người dùng khác!");
        }
        return matches.list(principal.id());
    }

    @GetMapping("/history")
    public List<MatchResponse> history(
            @AuthenticationPrincipal ApiPrincipal principal,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Long userId) {
        return list(principal, username, userId);
    }

    @GetMapping("/playable-submissions")
    public List<PlayableSubmission> playableSubmissions() {
        return matches.playableSubmissions();
    }

    @GetMapping("/{publicId}")
    public MatchResponse get(@AuthenticationPrincipal ApiPrincipal principal, @PathVariable UUID publicId) {
        if (principal == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập!");
        }
        return matches.get(principal.id(), publicId);
    }

    @GetMapping({"/{matchIdentifier}/replay", "/replays/{matchIdentifier}"})
    public ResponseEntity<?> replay(@PathVariable String matchIdentifier) {
        var info = matches.getReplayInfo(matchIdentifier);
        if (info.replayJson() != null && !info.replayJson().isBlank()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(info.replayJson());
        }
        if (info.storageKey() != null && !info.storageKey().isBlank() && !info.storageKey().startsWith("db://")) {
            try {
                byte[] bytes = objects.get(info.storageKey(), 16 * 1024 * 1024);
                if (bytes != null && bytes.length > 0) {
                    try (var gzipStream = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
                        String uncompressed = new String(gzipStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        return ResponseEntity.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(uncompressed);
                    } catch (Exception notGzip) {
                        return ResponseEntity.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception ignored) {}
        }
        throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Dữ liệu Replay chưa sẵn sàng hoặc đã bị xóa");
    }

    @GetMapping("/{publicId}/logs")
    public ResponseEntity<byte[]> botLog(@AuthenticationPrincipal ApiPrincipal principal,
                                         @PathVariable UUID publicId, @RequestParam String side) {
        byte[] bytes = objects.get(matches.botLogKey(principal.id(), publicId, side), 8 * 1024 * 1024);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/gzip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + publicId + "-bot-" + side + ".log.gz\"")
                .body(bytes);
    }
}
