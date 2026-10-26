package ottv2.api.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;

    public OutboxPublisher(JdbcTemplate jdbc, TransactionTemplate transaction,
                           RabbitTemplate rabbit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.rabbit = rabbit;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${ottv2.outbox.poll-ms:500}")
    public void publishBatch() {
        try {
            transaction.executeWithoutResult(status -> {
                List<OutboxRow> rows = jdbc.query("""
                        SELECT id, topic, payload::text AS payload FROM outbox_events
                        WHERE published=FALSE ORDER BY id LIMIT 20 FOR UPDATE SKIP LOCKED
                        """, (rs, row) -> new OutboxRow(rs.getLong("id"), rs.getString("topic"), rs.getString("payload")));
                for (OutboxRow row : rows) publishLocked(row);
            });
        } catch (Exception e) {
            log.warn("Outbox publish batch failed; rows will be retried", e);
        }
    }

    private void publishLocked(OutboxRow row) {
        try {
            CorrelationData correlation = new CorrelationData("outbox-" + row.id());
            rabbit.convertAndSend("", row.topic(), row.payload(), message -> {
                message.getMessageProperties().setContentType("application/json");
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                message.getMessageProperties().setTimestamp(new java.util.Date());
                return message;
            }, correlation);
            var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.ack() || correlation.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ did not confirm route for outbox " + row.id());
            }
            JsonNode payload = mapper.readTree(row.payload());
            if (row.topic().equals("compile.q")) {
                jdbc.update("UPDATE submissions SET status='COMPILING' WHERE id=? AND status='PENDING'",
                        payload.path("submissionId").asLong());
            }
            jdbc.update("UPDATE outbox_events SET published=TRUE WHERE id=?", row.id());
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish outbox event " + row.id(), e);
        }
    }

    private record OutboxRow(long id, String topic, String payload) { }
}
