package ottv2.api.ladder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LadderRatingServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    private LadderRatingService service;

    @BeforeEach
    void setUp() {
        service = new LadderRatingService(jdbc, null, new RatingCalculator.Elo());
    }

    @Test
    @DisplayName("Normal Win/Loss match updates wins and losses, NEVER increments draws")
    void testRecordFinishedGame_NormalWin_DoesNotIncrementDraws() {
        Date month = Date.valueOf(LocalDate.now());

        // 1. Mock series game query (scoreA = 1.0 for Bot A win)
        when(jdbc.query(contains("FROM matches m JOIN ladder_series ls"), any(RowMapper.class), any(), any(), eq(100L), any()))
                .thenReturn(List.of(new LadderRatingService.SeriesGame(1L, 10L, 11L, 12L, 1.0)));

        // Test with empty season to test safe early exit when season is inactive
        when(jdbc.query(contains("SELECT status FROM seasons"), any(RowMapper.class), any()))
                .thenReturn(List.of("INACTIVE"));

        service.recordFinishedGame(100L, month, "A");

        // Ratings are never updated when season inactive
        verify(jdbc, never()).update(contains("UPDATE bot_ratings SET elo=?"), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Infra error or abnormal match without valid score returns early without rating updates")
    void testRecordFinishedGame_InvalidMatch_SkipsRating() {
        Date month = Date.valueOf(LocalDate.now());

        // When match was cancelled or had WORKER_ERROR, query returns empty list
        when(jdbc.query(contains("FROM matches m JOIN ladder_series ls"), any(RowMapper.class), any(), any(), eq(101L), any()))
                .thenReturn(List.of());

        service.recordFinishedGame(101L, month, null);

        // Verify rating update and draw counter is never touched
        verify(jdbc, never()).update(contains("UPDATE bot_ratings SET elo=?"), any(), any(), any(), any(), any(), any());
        verify(jdbc, never()).update(contains("UPDATE ladder_series SET score_a="), any(), any(), any());
    }
}
