package io.metersphere.api.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {
    public final static String testTopic = "ms-api-exec-topic";

    @Bean
    public NewTopic apiExecTopic() {
        return TopicBuilder.name(testTopic)
                .build();
    }
}
