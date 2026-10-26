package ottv2.worker.compile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryPolicyTest {
    @Test
    void retriesUseIncreasingDelayQueuesThenDeadLetter() {
        assertEquals("compile.retry.5s", RetryPolicy.queueForRetry(0));
        assertEquals("compile.retry.30s", RetryPolicy.queueForRetry(1));
        assertEquals("compile.retry.120s", RetryPolicy.queueForRetry(2));
        assertEquals("compile.dlq", RetryPolicy.queueForRetry(3));
    }

    @Test
    void rejectsNegativeRetryCount() {
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.queueForRetry(-1));
    }
}
