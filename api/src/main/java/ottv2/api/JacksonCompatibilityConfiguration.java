package ottv2.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonCompatibilityConfiguration {
    @Bean
    ObjectMapper compileQueueObjectMapper() { return new ObjectMapper(); }
}
