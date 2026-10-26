package ottv2.api.bot;

import jakarta.validation.Valid;
import java.util.List;

import ottv2.api.bot.BotDtos.BotResponse;
import ottv2.api.bot.BotDtos.CreateBotRequest;
import ottv2.api.ladder.LadderController;
import ottv2.api.security.ApiPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/bots")
public class BotController {
    private final BotService botService;
    private final LadderController ladderController;

    public BotController(BotService botService, LadderController ladderController) {
        this.botService = botService;
        this.ladderController = ladderController;
    }

    @GetMapping
    public List<BotResponse> list(@AuthenticationPrincipal ApiPrincipal principal) {
        return botService.listForOwner(principal.id());
    }

    @GetMapping("/leaderboard")
    public List<LadderController.RankingEntry> leaderboard(
            @RequestParam(required = false) Long seasonId,
            @RequestParam(defaultValue = "100") @jakarta.validation.constraints.Min(1) int limit) {
        return ladderController.rankings(seasonId, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BotResponse create(@AuthenticationPrincipal ApiPrincipal principal,
                              @Valid @RequestBody CreateBotRequest request) {
        return botService.create(principal.id(), request.name());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal ApiPrincipal principal, @PathVariable long id) {
        boolean deleted = botService.delete(principal.id(), id);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bot not found");
        }
    }

    @GetMapping("/{id}")
    public BotDtos.BotDetailResponse get(@PathVariable long id) {
        return botService.getBotDetails(id);
    }

    @PostMapping("/{id}/set-ladder-active")
    public BotResponse setLadderActive(@AuthenticationPrincipal ApiPrincipal principal,
                                       @PathVariable long id,
                                       @RequestBody(required = false) BotDtos.SetLadderActiveRequest request) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập!");
        }
        Boolean active = request != null ? request.active() : null;
        return botService.setLadderActive(principal.id(), id, active);
    }
}
