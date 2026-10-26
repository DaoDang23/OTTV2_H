package ottv2.api.profile;

import java.util.List;
import ottv2.api.auth.AuthDtos.UserResponse;
import ottv2.api.auth.AuthService;
import ottv2.api.profile.ProfileDtos.EloHistoryItem;
import ottv2.api.profile.ProfileDtos.ProfileResponse;
import ottv2.api.profile.ProfileDtos.RecordMatchRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {
    @Mock JdbcTemplate jdbc;
    @Mock AuthService authService;
    ProfileService profileService;

    @BeforeEach
    void setUp() {
        profileService = new ProfileService(jdbc, authService);
    }

    @Test
    void getProfileReturnsCompleteAndAccurateData() {
        long userId = 42L;
        UserResponse user = new UserResponse(userId, "B22DCCN001", "vinh@ptit.edu.vn", "vinh", "STUDENT", 1232, 1216);
        when(authService.getUser(userId)).thenReturn(user);

        when(jdbc.queryForObject(contains("COUNT(*) FROM bots WHERE owner_id"), eq(Integer.class), eq(userId))).thenReturn(3);
        ProfileService.MatchStats mockStats = new ProfileService.MatchStats(5, 4, 1, 3, 2, 1, 2, 2, 0);
        when(jdbc.queryForObject(contains("FROM user_matches"), ArgumentMatchers.<RowMapper<ProfileService.MatchStats>>any(), eq(userId)))
                .thenReturn(mockStats);

        List<EloHistoryItem> mockHistory = List.of(
                new EloHistoryItem(10L, "Máy 2 - Phòng Thủ", 1150, "PVP", true, 16, 1232, "14:20:00"),
                new EloHistoryItem(9L, "Máy 1 - Tập Sự", 1050, "PVP", true, 16, 1216, "14:10:00")
        );
        when(jdbc.query(contains("FROM user_matches"), ArgumentMatchers.<RowMapper<EloHistoryItem>>any(), eq(userId)))
                .thenReturn(mockHistory);

        ProfileResponse profile = profileService.getProfile(userId);

        assertNotNull(profile);
        assertEquals("vinh", profile.user().displayName());
        assertEquals(1232, profile.pvpElo());
        assertEquals(1216, profile.botElo());
        assertEquals(3, profile.botsCount());
        assertEquals(5, profile.matchesCount());
        assertEquals(4, profile.wins());
        assertEquals(1, profile.losses());
        assertEquals(80, profile.winRate());
        assertEquals(3, profile.pvpMatchesCount());
        assertEquals(2, profile.pvpWins());
        assertEquals(1, profile.pvpLosses());
        assertEquals(2, profile.botMatchesCount());
        assertEquals(2, profile.botWins());
        assertEquals(0, profile.botLosses());
        assertEquals(2, profile.eloHistory().size());
        assertEquals(16, profile.eloHistory().get(0).change());
    }

    @Test
    void clientDirectRecordMatchCannotModifyPvPEloAndIsForcedToPractice() {
        long userId = 42L;
        UserResponse initialUser = new UserResponse(userId, "B22DCCN001", "vinh@ptit.edu.vn", "vinh", "STUDENT", 1200, 1200);
        when(authService.getUser(userId)).thenReturn(initialUser);

        RecordMatchRequest request = new RecordMatchRequest(
                "PVP", "NguyenVanA", 1200, "HUMAN",
                true, "USER", "TARGET_REACHED", 16, 1216, true
        );

        when(jdbc.update(contains("INSERT INTO user_matches"), eq(userId), eq("PVP"), eq("NguyenVanA"),
                eq(1200), eq("HUMAN"), eq(true), eq("USER"), eq("TARGET_REACHED"), eq(0), eq(1200), eq(false), eq("PRACTICE"), any(String.class)))
                .thenReturn(1);

        when(jdbc.queryForObject(contains("COUNT(*) FROM bots WHERE owner_id"), eq(Integer.class), eq(userId))).thenReturn(1);
        ProfileService.MatchStats mockStats = new ProfileService.MatchStats(1, 1, 0, 1, 1, 0, 0, 0, 0);
        when(jdbc.queryForObject(contains("FROM user_matches"), ArgumentMatchers.<RowMapper<ProfileService.MatchStats>>any(), eq(userId)))
                .thenReturn(mockStats);

        ProfileResponse result = profileService.recordMatch(userId, request);

        verify(jdbc, never()).update(contains("UPDATE users SET pvp_elo"));
        verify(jdbc).update(contains("INSERT INTO user_matches"), eq(userId), eq("PVP"), eq("NguyenVanA"),
                eq(1200), eq("HUMAN"), eq(true), eq("USER"), eq("TARGET_REACHED"), eq(0), eq(1200), eq(false), eq("PRACTICE"), any(String.class));

        assertEquals(1200, result.pvpElo());
        assertEquals(1, result.matchesCount());
        assertEquals(1, result.wins());
        assertEquals(0, result.losses());
        assertEquals(100, result.winRate());
    }

    @Test
    void practiceMatchDoesNotAffectElo() {
        long userId = 42L;
        UserResponse user = new UserResponse(userId, "B22DCCN001", "vinh@ptit.edu.vn", "vinh", "STUDENT", 1200, 1200);
        when(authService.getUser(userId)).thenReturn(user);

        RecordMatchRequest request = new RecordMatchRequest(
                "PVP", "Máy 1 - Tập Sự", 1050, "SPARRING",
                true, "USER", "TARGET_REACHED", 16, 1216, true
        );

        when(jdbc.update(contains("INSERT INTO user_matches"), eq(userId), eq("PVP"), eq("Máy 1 - Tập Sự"),
                eq(1050), eq("SPARRING"), eq(true), eq("USER"), eq("TARGET_REACHED"), eq(0), eq(1200), eq(false), eq("PRACTICE"), any(String.class)))
                .thenReturn(1);
        when(jdbc.queryForObject(contains("COUNT(*) FROM bots WHERE owner_id"), eq(Integer.class), eq(userId))).thenReturn(1);
        ProfileService.MatchStats mockStats = new ProfileService.MatchStats(0, 0, 0, 0, 0, 0, 0, 0, 0);
        when(jdbc.queryForObject(contains("FROM user_matches"), ArgumentMatchers.<RowMapper<ProfileService.MatchStats>>any(), eq(userId)))
                .thenReturn(mockStats);

        ProfileResponse result = profileService.recordMatch(userId, request);

        // Never update users pvp_elo or bot_elo on practice
        verify(jdbc, never()).update(contains("UPDATE users SET pvp_elo"));
        verify(jdbc, never()).update(contains("UPDATE users SET bot_elo"));
        verify(jdbc).update(contains("INSERT INTO user_matches"), eq(userId), eq("PVP"), eq("Máy 1 - Tập Sự"),
                eq(1050), eq("SPARRING"), eq(true), eq("USER"), eq("TARGET_REACHED"), eq(0), eq(1200), eq(false), eq("PRACTICE"), any(String.class));
        assertEquals(1200, result.pvpElo());
    }

    @Test
    void spoofedClientEloIsIgnoredAndNeverUpdatesDatabase() {
        long userId = 42L;
        UserResponse user = new UserResponse(userId, "B22DCCN001", "vinh@ptit.edu.vn", "vinh", "STUDENT", 1200, 1200);
        when(authService.getUser(userId)).thenReturn(user);

        RecordMatchRequest request = new RecordMatchRequest(
                "PVP", "OpponentHuman", 1200, "HUMAN",
                true, "USER", "TARGET_REACHED", 500000, 999999, true
        );

        when(jdbc.update(contains("INSERT INTO user_matches"), eq(userId), eq("PVP"), eq("OpponentHuman"),
                eq(1200), eq("HUMAN"), eq(true), eq("USER"), eq("TARGET_REACHED"), eq(0), eq(1200), eq(false), eq("PRACTICE"), any(String.class)))
                .thenReturn(1);
        when(jdbc.queryForObject(contains("COUNT(*) FROM bots WHERE owner_id"), eq(Integer.class), eq(userId))).thenReturn(1);
        when(jdbc.queryForObject(contains("FROM user_matches"), ArgumentMatchers.<RowMapper<ProfileService.MatchStats>>any(), eq(userId)))
                .thenReturn(new ProfileService.MatchStats(1, 1, 0, 1, 1, 0, 0, 0, 0));

        profileService.recordMatch(userId, request);

        verify(jdbc, never()).update(contains("UPDATE users SET pvp_elo"));
    }

    @Test
    void profileDataRemainsAccurateAfterPageReloadSimulation() {
        long userId = 77L;
        // User has played multiple matches, accumulated ratings and stats
        UserResponse persistedUser = new UserResponse(userId, "B22DCCN077", "reload_user@ptit.edu.vn", "ReloadTester", "STUDENT", 1350, 1420);
        when(authService.getUser(userId)).thenReturn(persistedUser);

        when(jdbc.queryForObject(contains("COUNT(*) FROM bots WHERE owner_id"), eq(Integer.class), eq(userId))).thenReturn(4);
        ProfileService.MatchStats mockStats = new ProfileService.MatchStats(12, 9, 3, 7, 5, 2, 5, 4, 1);
        when(jdbc.queryForObject(contains("FROM user_matches"), ArgumentMatchers.<RowMapper<ProfileService.MatchStats>>any(), eq(userId)))
                .thenReturn(mockStats);

        List<EloHistoryItem> eloHistory = List.of(
                new EloHistoryItem(101L, "Máy 5 - Đột Kích", 1310, "PVP", true, 22, 1350, "15:30:00"),
                new EloHistoryItem(100L, "DeepRPS-Grandmaster", 2450, "BOT", true, 30, 1420, "15:15:00")
        );
        when(jdbc.query(contains("FROM user_matches"), ArgumentMatchers.<RowMapper<EloHistoryItem>>any(), eq(userId)))
                .thenReturn(eloHistory);

        // Simulate reload: calling getProfile freshly as frontend mounts
        ProfileResponse reloadedProfile = profileService.getProfile(userId);

        assertNotNull(reloadedProfile);
        assertEquals(1350, reloadedProfile.pvpElo(), "PvP ELO must not reset to 1200 on reload");
        assertEquals(1420, reloadedProfile.botElo(), "Bot ELO must not reset to 1200 on reload");
        assertEquals(12, reloadedProfile.matchesCount(), "Total matches must not reset to 0 on reload");
        assertEquals(9, reloadedProfile.wins(), "Wins must not reset to 0 on reload");
        assertEquals(3, reloadedProfile.losses(), "Losses must not reset to 0 on reload");
        assertEquals(75, reloadedProfile.winRate(), "Win rate must remain accurate at 75% on reload");
        assertEquals(7, reloadedProfile.pvpMatchesCount(), "PvP matches count must reflect total database records");
        assertEquals(5, reloadedProfile.pvpWins(), "PvP wins must reflect total database records");
        assertEquals(2, reloadedProfile.pvpLosses(), "PvP losses must reflect total database records");
        assertEquals(5, reloadedProfile.botMatchesCount(), "Bot matches count must reflect total database records");
        assertEquals(4, reloadedProfile.botWins(), "Bot wins must reflect total database records");
        assertEquals(1, reloadedProfile.botLosses(), "Bot losses must reflect total database records");
        assertEquals(4, reloadedProfile.botsCount(), "Bots count must reflect owned bots");
        assertEquals(2, reloadedProfile.eloHistory().size(), "ELO history must not be wiped on reload");
        assertEquals("Máy 5 - Đột Kích", reloadedProfile.eloHistory().get(0).opponent());
    }

    @Test
    void updateProfileUpdatesStudentCodeAndDisplayName() {
        long userId = 77L;
        ProfileDtos.UpdateProfileRequest request = new ProfileDtos.UpdateProfileRequest("B22DCCN999", "New Name");

        UserResponse updatedUser = new UserResponse(userId, "B22DCCN999", "reload_user@ptit.edu.vn", "New Name", "STUDENT", 1350, 1420);
        when(authService.getUser(userId)).thenReturn(updatedUser);
        when(jdbc.queryForObject(contains("COUNT(*) FROM bots WHERE owner_id"), eq(Integer.class), eq(userId))).thenReturn(2);
        ProfileService.MatchStats mockStats = new ProfileService.MatchStats(0, 0, 0, 0, 0, 0, 0, 0, 0);
        when(jdbc.queryForObject(contains("FROM user_matches"), ArgumentMatchers.<RowMapper<ProfileService.MatchStats>>any(), eq(userId)))
                .thenReturn(mockStats);

        ProfileResponse response = profileService.updateProfile(userId, request);

        verify(jdbc).update(contains("UPDATE users SET student_code = ? WHERE id = ?"), eq("B22DCCN999"), eq(userId));
        verify(jdbc).update(contains("UPDATE users SET display_name = ? WHERE id = ?"), eq("New Name"), eq(userId));
        assertEquals("B22DCCN999", response.user().studentCode());
        assertEquals("New Name", response.user().displayName());
    }
}
