package ottv2.api.bot;

import java.util.List;

import ottv2.api.bot.BotDtos.BotResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BotServiceTest {
    @Mock JdbcTemplate jdbc;
    @InjectMocks BotService botService;

    @Test
    void listingAlwaysFiltersByAuthenticatedOwner() {
        List<BotResponse> expected = List.of(new BotResponse(3, "bot-a", true));
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<BotResponse>>any(), eq(42L)))
                .thenReturn(expected);

        assertEquals(expected, botService.listForOwner(42L));

        verify(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<BotResponse>>any(), eq(42L));
    }

    @Test
    void creatingBotUsesAuthenticatedOwnerId() {
        BotResponse expected = new BotResponse(4, "bot-b", true);
        when(jdbc.queryForObject(anyString(), ArgumentMatchers.<RowMapper<BotResponse>>any(), eq(42L), eq("bot-b")))
                .thenReturn(expected);

        assertEquals(expected, botService.create(42L, "bot-b"));

        verify(jdbc).queryForObject(anyString(), ArgumentMatchers.<RowMapper<BotResponse>>any(), eq(42L), eq("bot-b"));
    }

    @Test
    void deletingBotReturnsFalseWhenBotNotFoundOrNotOwned() {
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM bots"), eq(Integer.class), eq(99L), eq(42L)))
                .thenReturn(0);

        boolean result = botService.delete(42L, 99L);
        assertFalse(result);
    }

    @Test
    void deletingBotDeletesSuccessfully() {
        org.mockito.Mockito.doReturn(1)
                .when(jdbc).queryForObject(contains("SELECT COUNT(*) FROM bots"), eq(Integer.class), eq(5L), eq(42L));
        org.mockito.Mockito.doReturn(0)
                .when(jdbc).queryForObject(contains("SELECT COUNT(*) FROM match_participants"), eq(Integer.class), eq(5L));
        org.mockito.Mockito.doReturn(0)
                .when(jdbc).queryForObject(contains("SELECT COUNT(*) FROM ladder_series"), eq(Integer.class), eq(5L), eq(5L));
        org.mockito.Mockito.doReturn(1)
                .when(jdbc).update(contains("DELETE FROM bots WHERE id=?"), eq(5L), eq(42L));

        boolean result = botService.delete(42L, 5L);
        assertTrue(result);

        verify(jdbc).update(contains("UPDATE bots SET active_submission_id = NULL"), eq(5L), eq(42L));
        verify(jdbc).update(contains("DELETE FROM bot_ratings WHERE bot_id=?"), eq(5L));
        verify(jdbc).update(contains("DELETE FROM submissions WHERE bot_id=?"), eq(5L));
        verify(jdbc).update(contains("DELETE FROM bots WHERE id=?"), eq(5L), eq(42L));
    }

    @Test
    void deletingBotWithMatchHistoryPerformsSoftDeleteSafely() {
        org.mockito.Mockito.doReturn(1)
                .when(jdbc).queryForObject(contains("SELECT COUNT(*) FROM bots"), eq(Integer.class), eq(5L), eq(42L));
        org.mockito.Mockito.doReturn(3)
                .when(jdbc).queryForObject(contains("SELECT COUNT(*) FROM match_participants"), eq(Integer.class), eq(5L));

        boolean result = botService.delete(42L, 5L);
        assertTrue(result);

        verify(jdbc).update(contains("UPDATE bots SET active_submission_id = NULL"), eq(5L), eq(42L));
        verify(jdbc).update(contains("DELETE FROM bot_ratings WHERE bot_id=?"), eq(5L));
    }
}
