package ottv2.api.messaging;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableRabbit
public class RabbitTopology {
    @Bean Queue compileQueue() { return QueueBuilder.durable("compile.q").build(); }
    @Bean Queue matchQueue() { return QueueBuilder.durable("match.ladder.q").build(); }
    @Bean Queue tournamentMatchQueue() { return QueueBuilder.durable("match.tournament.q").build(); }
    @Bean Queue resultQueue() { return QueueBuilder.durable("result.q").build(); }
    @Bean Queue compileDeadLetterQueue() { return QueueBuilder.durable("compile.dlq").build(); }
    @Bean Queue matchDeadLetterQueue() { return QueueBuilder.durable("match.dlq").build(); }
}
