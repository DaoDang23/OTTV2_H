package ottv2.api.tournament;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ottv2.api.security.ApiPrincipal;
import ottv2.api.tournament.TournamentDtos.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/tournaments/{tournamentId}")
public class TournamentAdminController {

    private final TournamentAdminService adminService;
    private final JdbcTemplate jdbc;

    public TournamentAdminController(TournamentAdminService adminService, JdbcTemplate jdbc) {
        this.adminService = adminService;
        this.jdbc = jdbc;
    }

    @PostMapping({"/approve-team/{teamId}", "/teams/{teamId}/approve"})
    public TeamResponse approveTeam(@AuthenticationPrincipal ApiPrincipal principal,
                                    @PathVariable long tournamentId,
                                    @PathVariable long teamId) {
        checkAdminOrOrganizer(principal, tournamentId);
        return adminService.approveTeam(tournamentId, teamId, principal.id());
    }

    @PostMapping({"/disqualify-team/{teamId}", "/teams/{teamId}/disqualify"})
    public TeamResponse disqualifyTeam(@AuthenticationPrincipal ApiPrincipal principal,
                                       @PathVariable long tournamentId,
                                       @PathVariable long teamId,
                                       @Valid @RequestBody DisqualifyTeamRequest request) {
        checkAdminOrOrganizer(principal, tournamentId);
        return adminService.disqualifyTeam(tournamentId, teamId, principal.id(), request.reason());
    }

    @PostMapping({"/open-registration", "/open"})
    public TournamentResponse openRegistration(@AuthenticationPrincipal ApiPrincipal principal,
                                               @PathVariable("tournamentId") long tournamentId) {
        checkAdminOrOrganizer(principal, tournamentId);
        return adminService.openRegistration(tournamentId, principal.id());
    }

    @PostMapping("/lock-registration")
    public TournamentResponse lockRegistration(@AuthenticationPrincipal ApiPrincipal principal,
                                               @PathVariable("tournamentId") long tournamentId) {
        checkAdminOrOrganizer(principal, tournamentId);
        return adminService.lockRegistration(tournamentId, principal.id());
    }

    @PostMapping("/start")
    public TournamentResponse startTournament(@AuthenticationPrincipal ApiPrincipal principal,
                                              @PathVariable long tournamentId) {
        checkAdminOrOrganizer(principal, tournamentId);
        return adminService.startTournament(tournamentId, principal.id());
    }

    @PostMapping("/suspend")
    public TournamentResponse suspendTournament(@AuthenticationPrincipal ApiPrincipal principal,
                                                @PathVariable long tournamentId,
                                                @Valid @RequestBody ActionWithReasonRequest request) {
        checkAdminOrOrganizer(principal, tournamentId);
        return adminService.suspendTournament(tournamentId, principal.id(), request.reason());
    }

    @PostMapping("/resume")
    public TournamentResponse resumeTournament(@AuthenticationPrincipal ApiPrincipal principal,
                                               @PathVariable long tournamentId) {
        checkAdminOrOrganizer(principal, tournamentId);
        return adminService.resumeTournament(tournamentId, principal.id());
    }

    @PostMapping("/cancel")
    public TournamentResponse cancelTournament(@AuthenticationPrincipal ApiPrincipal principal,
                                               @PathVariable long tournamentId,
                                               @Valid @RequestBody ActionWithReasonRequest request) {
        checkAdminOrOrganizer(principal, tournamentId);
        return adminService.cancelTournament(tournamentId, principal.id(), request.reason());
    }

    @DeleteMapping({"", "/"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTournament(@AuthenticationPrincipal ApiPrincipal principal,
                                 @PathVariable("tournamentId") long tournamentId) {
        checkAdminOrOrganizer(principal, tournamentId);
        adminService.deleteTournament(tournamentId, principal.id());
    }

    @PostMapping("/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTournamentPost(@AuthenticationPrincipal ApiPrincipal principal,
                                     @PathVariable("tournamentId") long tournamentId) {
        checkAdminOrOrganizer(principal, tournamentId);
        adminService.deleteTournament(tournamentId, principal.id());
    }

    @PostMapping("/matches/{matchId}/resolve-dispute")
    public TournamentMatchResponse resolveDispute(@AuthenticationPrincipal ApiPrincipal principal,
                                                 @PathVariable long tournamentId,
                                                 @PathVariable long matchId,
                                                 @Valid @RequestBody ResolveDisputeRequest request) {
        checkAdminOrOrganizer(principal, tournamentId);
        return adminService.resolveMatchDispute(tournamentId, matchId, principal.id(), request);
    }

    @PostMapping("/matches/{matchId}/start")
    public TournamentMatchResponse startMatch(@AuthenticationPrincipal ApiPrincipal principal,
                                              @PathVariable long tournamentId,
                                              @PathVariable long matchId) {
        checkAdminOrOrganizer(principal, tournamentId);
        return adminService.startMatch(tournamentId, matchId, principal.id());
    }

    @PostMapping("/matches/{matchId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retryMatch(@AuthenticationPrincipal ApiPrincipal principal,
                           @PathVariable long tournamentId,
                           @PathVariable long matchId) {
        checkAdminOrOrganizer(principal, tournamentId);
        adminService.retryMatch(tournamentId, matchId, principal.id());
    }

    public void checkAdminOrOrganizer(ApiPrincipal principal, long tournamentId) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if ("ADMIN".equals(principal.role())) {
            return;
        }

        List<Long> creators = jdbc.query("SELECT created_by FROM tournaments WHERE id = ?",
                (rs, row) -> (Long) rs.getObject("created_by"), tournamentId);

        if (creators.isEmpty() || creators.getFirst() == null || !creators.getFirst().equals(principal.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin or tournament organizer permission required");
        }
    }
}
