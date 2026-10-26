package ottv2.worker.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import ottv2.worker.compile.ObjectStorage;
import ottv2.worker.match.MatchExecutionService.MatchOutcome;
import ottv2.worker.match.MatchResultEvent;

/** RabbitMQ match consumer. Configure a unique ISOLATE_BOX_ID for each worker process. */
public final class MatchWorkerCommand {
    private static final String MATCH_QUEUE = "match.ladder.q";
    private static final String TOURNAMENT_QUEUE = "match.tournament.q";
    private static final String RESULT_QUEUE = "result.q";
    private static final String DEAD_QUEUE = "match.dlq";
    private static final String[] RETRY_QUEUES = {"match.retry.5s", "match.retry.30s", "match.retry.120s"};
    private static final int[] RETRY_DELAYS = {5_000, 30_000, 120_000};

    private MatchWorkerCommand() { }

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String endpoint = env("MINIO_ENDPOINT", "http://localhost:9000");
        String bucket = env("MINIO_BUCKET", "ottv2");
        ObjectStorage store = new ObjectStorage(endpoint, env("MINIO_ROOT_USER", "ottv2"),
                env("MINIO_ROOT_PASSWORD", "ottv2-local-secret"), bucket);
        MatchExecutionService.ObjectStorageFacade storage = new MatchExecutionService.ObjectStorageFacade() {
            @Override public byte[] get(String key, int maxBytes) throws Exception { return store.get(key, maxBytes); }
            @Override public void put(String key, byte[] value, String contentType) throws Exception {
                store.put(key, value, contentType);
            }
        };
        ArtifactCache cache = new ArtifactCache(
                Long.parseLong(env("MATCH_ARTIFACT_CACHE_MB", "512")) * 1024L * 1024L);
        MatchExecutionService execution = new MatchExecutionService(mapper, storage, cache,
                isolateBoxId(), env("ISOLATE_EXECUTABLE", "isolate"),
                Path.of(env("ISOLATE_BOX_ROOT", "/var/lib/isolate/{id}/box")));
        String workerId = env("MATCH_WORKER_ID", defaultWorkerId());
        WorkerMetrics metrics = new WorkerMetrics(Integer.parseInt(env("WORKER_METRICS_PORT", "9404")));

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(env("RABBITMQ_HOST", "localhost"));
        factory.setPort(Integer.parseInt(env("RABBITMQ_PORT", "5672")));
        factory.setUsername(env("RABBITMQ_USER", "ottv2"));
        factory.setPassword(env("RABBITMQ_PASSWORD", "ottv2-local"));
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(2_000);

        try (Connection connection = factory.newConnection("ottv2-match-worker");
             Channel channel = connection.createChannel()) {
            declareQueues(channel);
            channel.basicQos(1, true);
            channel.confirmSelect();
            DefaultConsumer matchConsumer = new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String consumerTag, Envelope envelope,
                                           AMQP.BasicProperties properties, byte[] body) {
                    long deliveryTag = envelope.getDeliveryTag();
                    MatchJob job = null;
                    var sample = metrics.begin(properties.getTimestamp());
                    boolean succeeded = false;
                    try {
                        job = mapper.readValue(body, MatchJob.class);
                        publish(channel, RESULT_QUEUE, mapper.writeValueAsBytes(MatchResultEvent.started(job, workerId)));
                        MatchOutcome outcome = execution.execute(job);
                        publish(channel, RESULT_QUEUE, mapper.writeValueAsBytes(MatchResultEvent.finished(outcome, workerId)));
                        channel.basicAck(deliveryTag, false);
                        succeeded = true;
                    } catch (Exception failure) {
                        failure.printStackTrace();
                        try {
                            retryOrDeadLetter(channel, envelope, properties, body, failure, mapper, job, workerId);
                            channel.basicAck(deliveryTag, false);
                        } catch (Exception publishFailure) {
                            try { channel.basicNack(deliveryTag, false, true); } catch (Exception ignored) { }
                        }
                    } finally {
                        if (succeeded) metrics.completed(sample); else metrics.failed(sample);
                    }
                }
            };
            channel.basicConsume(TOURNAMENT_QUEUE, false, matchConsumer);
            channel.basicConsume(MATCH_QUEUE, false, matchConsumer);
            while (connection.isOpen()) TimeUnit.SECONDS.sleep(1);
        }
    }

    private static void declareQueues(Channel channel) throws Exception {
        channel.queueDeclare(MATCH_QUEUE, true, false, false, null);
        channel.queueDeclare(TOURNAMENT_QUEUE, true, false, false, null);
        channel.queueDeclare(RESULT_QUEUE, true, false, false, null);
        channel.queueDeclare(DEAD_QUEUE, true, false, false, null);
        for (int i = 0; i < RETRY_QUEUES.length; i++) {
            Map<String, Object> args = new HashMap<>();
            args.put("x-message-ttl", RETRY_DELAYS[i]);
            args.put("x-dead-letter-exchange", "");
            args.put("x-dead-letter-routing-key", MATCH_QUEUE);
            channel.queueDeclare(RETRY_QUEUES[i], true, false, false, args);
        }
    }

    private static void retryOrDeadLetter(Channel channel, Envelope envelope, AMQP.BasicProperties original,
                                          byte[] body, Exception failure, ObjectMapper mapper,
                                          MatchJob job, String workerId) throws Exception {
        int retries = 0;
        Object previous = original.getHeaders() == null ? null : original.getHeaders().get("x-retry-count");
        if (previous instanceof Number number) retries = number.intValue();
        Map<String, Object> headers = new HashMap<>();
        if (original.getHeaders() != null) headers.putAll(original.getHeaders());
        String target;
        if (retries < RETRY_QUEUES.length) {
            target = RETRY_QUEUES[retries];
            headers.put("x-retry-count", retries + 1);
        } else {
            target = DEAD_QUEUE;
            headers.put("x-error", safeMessage(failure));
        }
        publish(channel, target, body, new AMQP.BasicProperties.Builder()
                .contentType(original.getContentType()).deliveryMode(2).headers(headers).build());
        if (target.equals(DEAD_QUEUE) && job != null) {
            publish(channel, RESULT_QUEUE, mapper.writeValueAsBytes(MatchResultEvent.failed(job, workerId, failure)));
        }
    }

    private static void publish(Channel channel, String queue, byte[] body) throws Exception {
        publish(channel, queue, body, null);
    }

    private static void publish(Channel channel, String queue, byte[] body, AMQP.BasicProperties properties) throws Exception {
        AMQP.BasicProperties persistent = properties == null
                ? new AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2).build()
                : properties;
        channel.basicPublish("", queue, true, persistent, body);
        if (!channel.waitForConfirms(5_000)) throw new IllegalStateException("RabbitMQ did not confirm publish to " + queue);
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        return message.length() <= 2048 ? message : message.substring(0, 2048);
    }

    private static String defaultWorkerId() {
        try { return InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid(); }
        catch (Exception e) { return "match-worker-" + ProcessHandle.current().pid(); }
    }

    private static int isolateBoxId() {
        String explicit = System.getenv("ISOLATE_BOX_ID");
        if (explicit != null && !explicit.isBlank()) return Integer.parseInt(explicit);
        String pod = env("POD_NAME", env("HOSTNAME", ""));
        int separator = pod.lastIndexOf('-');
        if (separator >= 0 && separator < pod.length() - 1) {
            try {
                int ordinal = Integer.parseInt(pod.substring(separator + 1));
                return Integer.parseInt(env("ISOLATE_BOX_BASE_ID", "200")) + ordinal * 2;
            } catch (NumberFormatException ignored) { }
        }
        return 200;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
