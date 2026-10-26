package ottv2.worker.compile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Run with java -cp worker.jar ottv2.worker.compile.CompileWorkerCommand. */
public final class CompileWorkerCommand {
    private static final String COMPILE_QUEUE = "compile.q";
    private static final String RESULT_QUEUE = "result.q";
    private static final String DEAD_QUEUE = "compile.dlq";
    private static final String[] RETRY_QUEUES = {"compile.retry.5s", "compile.retry.30s", "compile.retry.120s"};
    private static final int[] RETRY_DELAYS = {5_000, 30_000, 120_000};

    private CompileWorkerCommand() { }

    public static void main(String[] args) throws Exception {
        String endpoint = env("MINIO_ENDPOINT", "http://localhost:9000");
        String bucket = env("MINIO_BUCKET", "ottv2");
        String workerJar = System.getProperty("ottv2.worker.jar", detectWorkerJar());
        ObjectStorage storage = new ObjectStorage(endpoint, env("MINIO_ROOT_USER", "ottv2"),
                env("MINIO_ROOT_PASSWORD", "ottv2-local-secret"), bucket);
        BotCompiler compiler = new IsolateCompiler(env("ISOLATE_EXECUTABLE", "isolate"),
                Integer.parseInt(env("ISOLATE_BOX_ID", "100")), workerJar,
                Integer.parseInt(env("COMPILE_MAX_SOURCE_BYTES", "262144")));
        ObjectMapper mapper = new ObjectMapper();

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(env("RABBITMQ_HOST", "localhost"));
        factory.setPort(Integer.parseInt(env("RABBITMQ_PORT", "5672")));
        factory.setUsername(env("RABBITMQ_USER", "ottv2"));
        factory.setPassword(env("RABBITMQ_PASSWORD", "ottv2-local"));
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(2_000);

        try (Connection connection = factory.newConnection("ottv2-compile-worker"); Channel channel = connection.createChannel()) {
            declareQueues(channel);
            channel.basicQos(1);
            channel.confirmSelect();
            channel.basicConsume(COMPILE_QUEUE, false, new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties props, byte[] body) {
                    long tag = envelope.getDeliveryTag();
                    try {
                        CompileJob job = mapper.readValue(body, CompileJob.class);
                        byte[] source = storage.get(job.sourceKey(),
                                Integer.parseInt(env("COMPILE_MAX_SOURCE_BYTES", "262144")));
                        CompileOutcome outcome = compiler.compile(job, source);
                        CompileResult result = outcome.result();
                        if (result.status().equals("COMPILED")) {
                            String contentType = switch (job.language()) {
                                case "JAVA" -> "application/java-archive";
                                case "CPP" -> "application/octet-stream";
                                case "PYTHON" -> "text/x-python; charset=utf-8";
                                default -> "application/octet-stream";
                            };
                            storage.put(result.artifactKey(), outcome.artifactBytes(), contentType);
                        }
                        publish(channel, RESULT_QUEUE, mapper.writeValueAsBytes(result), null);
                        channel.basicAck(tag, false);
                    } catch (Exception failure) {
                        try {
                            retryOrDeadLetter(channel, envelope, props, body, failure, mapper);
                            channel.basicAck(tag, false);
                        } catch (Exception publishFailure) {
                            try { channel.basicNack(tag, false, true); } catch (Exception ignored) { }
                        }
                    }
                }
            });
            while (connection.isOpen()) TimeUnit.SECONDS.sleep(1);
        }
    }

    private static void declareQueues(Channel channel) throws Exception {
        channel.queueDeclare(COMPILE_QUEUE, true, false, false, null);
        channel.queueDeclare(RESULT_QUEUE, true, false, false, null);
        channel.queueDeclare(DEAD_QUEUE, true, false, false, null);
        for (int i = 0; i < RETRY_QUEUES.length; i++) {
            Map<String, Object> args = new HashMap<>();
            args.put("x-message-ttl", RETRY_DELAYS[i]);
            args.put("x-dead-letter-exchange", "");
            args.put("x-dead-letter-routing-key", COMPILE_QUEUE);
            channel.queueDeclare(RETRY_QUEUES[i], true, false, false, args);
        }
    }

    private static void retryOrDeadLetter(Channel channel, Envelope envelope, AMQP.BasicProperties original,
                                          byte[] body, Exception failure, ObjectMapper mapper) throws Exception {
        Object header = original.getHeaders() == null ? null : original.getHeaders().get("x-retry-count");
        int retries = header instanceof Number number ? number.intValue() : 0;
        Map<String, Object> headers = new HashMap<>();
        if (original.getHeaders() != null) headers.putAll(original.getHeaders());
        String target = RetryPolicy.queueForRetry(retries);
        if (retries < RETRY_QUEUES.length) {
            headers.put("x-retry-count", retries + 1);
        } else {
            headers.put("x-error", safeMessage(failure));
        }
        publish(channel, target, body, new AMQP.BasicProperties.Builder()
                .contentType(original.getContentType()).deliveryMode(2).headers(headers).build());
        if (target.equals(DEAD_QUEUE)) {
            CompileJob job = mapper.readValue(body, CompileJob.class);
            CompileResult exhausted = new CompileResult(job.submissionId(), "COMPILE_ERROR", null,
                    "Compile worker failed after three infrastructure retries; message sent to compile.dlq: "
                            + safeMessage(failure));
            publish(channel, RESULT_QUEUE, mapper.writeValueAsBytes(exhausted), null);
        }
    }

    private static void publish(Channel channel, String queue, byte[] body, AMQP.BasicProperties properties) throws Exception {
        AMQP.BasicProperties persistent = properties == null
                ? new AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2).build()
                : properties;
        channel.basicPublish("", queue, true, persistent, body);
        if (!channel.waitForConfirms(5_000)) throw new IllegalStateException("RabbitMQ did not confirm publish to " + queue);
    }

    private static String safeMessage(Exception failure) {
        String text = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        return text.length() <= 2048 ? text : text.substring(0, 2048);
    }

    private static String detectWorkerJar() {
        try {
            return java.nio.file.Path.of(com.fasterxml.jackson.databind.ObjectMapper.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot locate the worker jar containing Jackson", e);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
