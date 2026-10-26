package ottv2.worker.compile;

final class RetryPolicy {
    private static final String[] QUEUES = {"compile.retry.5s", "compile.retry.30s", "compile.retry.120s"};

    private RetryPolicy() { }

    static String queueForRetry(int retriesAlreadyMade) {
        if (retriesAlreadyMade < 0) throw new IllegalArgumentException("Retry count cannot be negative");
        return retriesAlreadyMade < QUEUES.length ? QUEUES[retriesAlreadyMade] : "compile.dlq";
    }
}
