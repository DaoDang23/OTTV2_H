package ottv2.api.tournament;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ottv2.api.security.ApiPrincipal;
import ottv2.api.tournament.TournamentDtos.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tournaments")
public class TournamentController {
    private final TournamentService tournaments;
    private final TournamentBracketService bracketService;
    private final TournamentExecutionService executionService;
    private final TournamentAdminService adminService;

    public TournamentController(TournamentService tournaments,
                                TournamentBracketService bracketService,
                                TournamentExecutionService executionService) {
        this(tournaments, bracketService, executionService, null);
    }

    @Autowired
    public TournamentController(TournamentService tournaments,
                                TournamentBracketService bracketService,
                                TournamentExecutionService executionService,
                                TournamentAdminService adminService) {
        this.tournaments = tournaments;
        this.bracketService = bracketService;
        this.executionService = executionService;
        this.adminService = adminService;
    }

    // =========================================================================
    // Tournament Management & Lifecycle
    // =========================================================================

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Object create(@AuthenticationPrincipal ApiPrincipal principal,
                         @Valid @RequestBody CreateTournamentRequest request) {
        requireAdmin(principal);
        return tournaments.createTournament(principal != null ? principal.id() : null, request);
    }

    @GetMapping
    public List<TournamentResponse> list() {
        return tournaments.listTournaments();
    }

    @GetMapping("/my")
    public List<UserTournamentParticipation> getMyTournaments(@AuthenticationPrincipal ApiPrincipal principal) {
        requireAuthenticated(principal);
        return tournaments.getUserTournaments(principal.id());
    }

    @GetMapping("/{tournamentId}")
    public Object get(@PathVariable long tournamentId,
                      @RequestParam(required = false, defaultValue = "details") String view) {
        if ("bracket".equalsIgnoreCase(view)) {
            return bracketService.getBracketView(tournamentId);
        }
        return tournaments.getTournament(tournamentId);
    }

    @PostMapping({"/{tournamentId}/open-registration", "/{tournamentId}/open"})
    public TournamentResponse openRegistration(@AuthenticationPrincipal ApiPrincipal principal,
                                               @PathVariable long tournamentId) {
        requireAdmin(principal);
        if (adminService != null) {
            return adminService.openRegistration(tournamentId, principal != null ? principal.id() : null);
        }
        return tournaments.changeTournamentStatus(tournamentId, principal != null ? principal.id() : null,
                new ChangeStatusRequest(TournamentStatus.REGISTRATION_OPEN, "Open registration"));
    }

    @PostMapping("/{tournamentId}/start")
    public TournamentResponse startTournament(@AuthenticationPrincipal ApiPrincipal principal,
                                              @PathVariable long tournamentId) {
        requireAdmin(principal);
        return executionService.startTournament(tournamentId, principal != null ? principal.id() : null);
    }

    @GetMapping("/{tournamentId}/bracket")
    public BracketViewResponse getBracket(@PathVariable long tournamentId) {
        return bracketService.getBracketView(tournamentId);
    }

    @GetMapping("/{tournamentId}/matches")
    public List<TournamentMatchResponse> listMatches(@PathVariable long tournamentId) {
        return bracketService.listMatches(tournamentId);
    }

    @GetMapping("/{tournamentId}/standings")
    public List<StandingRow> listStandings(@PathVariable long tournamentId) {
        return bracketService.listStandings(tournamentId);
    }

    @PostMapping("/{tournamentId}/matches/{matchId}/retry-match")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retryTournamentMatch(@AuthenticationPrincipal ApiPrincipal principal,
                                     @PathVariable long tournamentId,
                                     @PathVariable long matchId) {
        requireAdmin(principal);
        executionService.retryFailedMatch(tournamentId, matchId, principal != null ? principal.id() : null);
    }

    @GetMapping("/by-slug/{slug}")
    public TournamentResponse getBySlug(@PathVariable String slug) {
        return tournaments.getTournamentBySlug(slug);
    }

    @PutMapping("/{tournamentId}")
    public TournamentResponse update(@AuthenticationPrincipal ApiPrincipal principal,
                                     @PathVariable long tournamentId,
                                     @Valid @RequestBody UpdateTournamentRequest request) {
        requireAdmin(principal);
        return tournaments.updateTournament(tournamentId, principal.id(), request);
    }

    @PostMapping("/{tournamentId}/status")
    public TournamentResponse changeStatus(@AuthenticationPrincipal ApiPrincipal principal,
                                           @PathVariable long tournamentId,
                                           @Valid @RequestBody ChangeStatusRequest request) {
        requireAdmin(principal);
        return tournaments.changeTournamentStatus(tournamentId, principal.id(), request);
    }

    // =========================================================================
    // Team Registration & Bot Check-In
    // =========================================================================

    @PostMapping("/{tournamentId}/teams")
    @ResponseStatus(HttpStatus.CREATED)
    public TeamResponse registerTeam(@AuthenticationPrincipal ApiPrincipal principal,
                                     @PathVariable long tournamentId,
                                     @Valid @RequestBody RegisterTeamRequest request) {
        requireAuthenticated(principal);
        return tournaments.registerTeam(tournamentId, principal.id(), request);
    }

    @GetMapping("/{tournamentId}/teams")
    public List<TeamResponse> listTeams(@PathVariable long tournamentId) {
        return tournaments.listTeams(tournamentId);
    }

    @GetMapping("/{tournamentId}/teams/{teamId}")
    public TeamResponse getTeam(@PathVariable long tournamentId, @PathVariable long teamId) {
        return tournaments.getTeam(tournamentId, teamId);
    }

    @PostMapping("/{tournamentId}/teams/{teamId}/check-in")
    public TeamResponse checkInTeam(@AuthenticationPrincipal ApiPrincipal principal,
                                    @PathVariable long tournamentId,
                                    @PathVariable long teamId,
                                    @RequestBody(required = false) CheckInTeamRequest request) {
        requireAuthenticated(principal);
        return tournaments.checkInTeam(tournamentId, teamId, principal.id(), request);
    }

    // =========================================================================
    // Audit Logs
    // =========================================================================

    @GetMapping("/{tournamentId}/audit-logs")
    public List<AuditLogResponse> listAuditLogs(@PathVariable long tournamentId) {
        return tournaments.listAuditLogs(tournamentId);
    }

    // =========================================================================
    // Legacy Endpoints (Compatibility)
    // =========================================================================

    @PostMapping("/{tournamentId}/seed")
    public TournamentService.TournamentView seed(@AuthenticationPrincipal ApiPrincipal principal,
                                                 @PathVariable long tournamentId) {
        requireAdmin(principal);
        return tournaments.seed(tournamentId);
    }

    @PostMapping("/{tournamentId}/matches/{publicId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retry(@AuthenticationPrincipal ApiPrincipal principal,
                      @PathVariable long tournamentId,
                      @PathVariable UUID publicId) {
        requireAdmin(principal);
        tournaments.retryFailedMatch(tournamentId, publicId);
    }

    private static void requireAuthenticated(ApiPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
    }

    private static void requireAdmin(ApiPrincipal principal) {
        if (principal == null || !"ADMIN".equals(principal.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
    }

    public record CreateRequest(@NotNull Long seasonId, @NotBlank @Size(max = 100) String name) { }
}
