package ottv2.api.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import ottv2.api.ladder.LadderRatingService;
import ottv2.api.tournament.TournamentResultService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchResultConsumerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void persistsFinishedMatchAndReplayMetadataAtomically() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            invocation.<Consumer<TransactionStatus>>getArgument(0).accept(mock(TransactionStatus.class));
            return null;
        }).when(transaction).executeWithoutResult(any());
        MatchResultConsumer consumer = new MatchResultConsumer(jdbc, transaction, mapper, mock(LadderRatingService.class), mock(TournamentResultService.class));
        byte[] body = mapper.writeValueAsBytes(new MatchResultConsumer.MatchEvent("MATCH_FINISHED", 51,
                "2026-10-01", "worker-1", "A", "TARGET_REACHED", 12, null, null,
                null, null, null, null, "matches/id/replay.json.gz", 123, "matches/id/a.gz", "matches/id/b.gz", null));

        consumer.consume(body);

        verify(jdbc).update(contains("UPDATE matches SET status='FINISHED'"), eq("A"), eq("TARGET_REACHED"),
                eq(12), eq(51L), any());
        verify(jdbc).update(contains("INSERT INTO replays"), eq(51L), any(), eq("matches/id/replay.json.gz"),
                eq(123), eq("matches/id/a.gz"), eq("matches/id/b.gz"));
    }

    @Test
    void acknowledgesStartedEventWithoutAttemptingToWriteMatchResults() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MatchResultConsumer consumer = new MatchResultConsumer(jdbc, mock(TransactionTemplate.class), mapper, mock(LadderRatingService.class), mock(TournamentResultService.class));
        byte[] body = mapper.writeValueAsBytes(new MatchResultConsumer.MatchEvent("MATCH_STARTED", 51,
                "2026-10-01", "worker-1", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null));

        consumer.consume(body);

        verify(jdbc).update(contains("SET status='RUNNING'"), eq("worker-1"), eq(51L), any());
    }

    @Test
    void duplicateFinishedEventDoesNotRewriteParticipantsOrReplay() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AtomicInteger finishWrites = new AtomicInteger();
        when(jdbc.update(any(String.class), any(Object[].class))).thenAnswer(invocation ->
                invocation.<String>getArgument(0).contains("UPDATE matches SET status='FINISHED'")
                        ? (finishWrites.getAndIncrement() == 0 ? 1 : 0) : 1);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            invocation.<Consumer<TransactionStatus>>getArgument(0).accept(mock(TransactionStatus.class));
            return null;
        }).when(transaction).executeWithoutResult(any());
        MatchResultConsumer consumer = new MatchResultConsumer(jdbc, transaction, mapper, mock(LadderRatingService.class), mock(TournamentResultService.class));
        byte[] body = mapper.writeValueAsBytes(new MatchResultConsumer.MatchEvent("MATCH_FINISHED", 51,
                "2026-10-01", "worker-1", "A", "TARGET_REACHED", 12, null, null,
                null, null, null, null, "matches/id/replay.json.gz", 123, "matches/id/a.gz", "matches/id/b.gz", null));

        consumer.consume(body);
        consumer.consume(body);

        verify(jdbc, times(2)).update(contains("UPDATE matches SET status='FINISHED'"), any(Object[].class));
        verify(jdbc).update(contains("UPDATE match_participants SET fault=CASE"), any(Object[].class));
        verify(jdbc).update(contains("INSERT INTO replays"), any(Object[].class));
    }

    @Test
    void persistsExhaustedWorkerFailureAndMarksBothParticipantsAsInfrastructureFaults() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            invocation.<Consumer<TransactionStatus>>getArgument(0).accept(mock(TransactionStatus.class));
            return null;
        }).when(transaction).executeWithoutResult(any());
        MatchResultConsumer consumer = new MatchResultConsumer(jdbc, transaction, mapper, mock(LadderRatingService.class), mock(TournamentResultService.class));
        byte[] body = mapper.writeValueAsBytes(new MatchResultConsumer.MatchEvent("MATCH_FAILED", 51,
                "2026-10-01", "worker-1", null, null, null, null, null,
                null, null, null, null, null, null, null, null, "MinIO unavailable"));

        consumer.consume(body);

        verify(jdbc).update(contains("status='ERROR'"), eq("MinIO unavailable"), eq(51L), any());
        verify(jdbc).update(contains("SET fault='INFRA_ERROR'"), eq(51L), any());
    }

    @Test
    void tournamentMatchDoesNotBleedIntoLadderRatings() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(contains("SELECT match_type FROM matches"), eq(String.class), eq(99L)))
                .thenReturn("TOURNAMENT");
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            invocation.<Consumer<TransactionStatus>>getArgument(0).accept(mock(TransactionStatus.class));
            return null;
        }).when(transaction).executeWithoutResult(any());
        LadderRatingService ladderRatings = mock(LadderRatingService.class);
        TournamentResultService tournamentResults = mock(TournamentResultService.class);
        MatchResultConsumer consumer = new MatchResultConsumer(jdbc, transaction, mapper, ladderRatings, tournamentResults);

        byte[] body = mapper.writeValueAsBytes(new MatchResultConsumer.MatchEvent("MATCH_FINISHED", 99,
                "2026-10-01", "worker-1", "A", "TARGET_REACHED", 15, null, null,
                null, null, null, null, "matches/99/replay.json.gz", 123, "matches/99/a.gz", "matches/99/b.gz", null));

        consumer.consume(body);

        // ladderRatings MUST NEVER be called for TOURNAMENT matches
        org.mockito.Mockito.verify(ladderRatings, times(0)).recordFinishedGame(any(Long.class), any(), any());
        // tournamentResults MUST be called
        org.mockito.Mockito.verify(tournamentResults, times(1)).recordFinishedGame(eq(99L), any());
    }
}
