package ottv2.api.profile;

import jakarta.validation.Valid;
import ottv2.api.profile.ProfileDtos.ProfileResponse;
import ottv2.api.profile.ProfileDtos.RecordMatchRequest;
import ottv2.api.profile.ProfileDtos.UpdateProfileRequest;
import ottv2.api.security.ApiPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {
    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public ProfileResponse getProfile(@AuthenticationPrincipal ApiPrincipal principal) {
        return profileService.getProfile(principal.id());
    }

    @PostMapping("/matches")
    public ProfileResponse recordMatch(@AuthenticationPrincipal ApiPrincipal principal,
                                       @Valid @RequestBody RecordMatchRequest request) {
        return profileService.recordMatch(principal.id(), request);
    }

    @PutMapping
    public ProfileResponse updateProfile(@AuthenticationPrincipal ApiPrincipal principal,
                                         @RequestBody UpdateProfileRequest request) {
        return profileService.updateProfile(principal.id(), request);
    }
}
