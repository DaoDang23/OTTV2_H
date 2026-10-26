package ottv2.api.submission;

import com.fasterxml.jackson.databind.ObjectMapper;
import ottv2.api.storage.SourceObjectStore;
import ottv2.api.submission.SubmissionDtos.CreateSubmissionRequest;
import ottv2.api.submission.SubmissionDtos.Language;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SubmissionServiceTest {
    @Test
    void rejectsUtf8SourceLargerThanLimitBeforeStorageOrDatabaseWork() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        SourceObjectStore objectStore = mock(SourceObjectStore.class);
        SubmissionService service = new SubmissionService(jdbc, transaction, objectStore,
                new ObjectMapper(), 3, 30, 50);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.submit(10, 20, new CreateSubmissionRequest(Language.PYTHON, "éé")));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, error.getStatusCode());
        verifyNoInteractions(jdbc, transaction, objectStore);
    }
}
