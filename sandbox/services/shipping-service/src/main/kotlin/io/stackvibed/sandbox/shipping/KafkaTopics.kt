package io.stackvibed.sandbox.shipping

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Creates the topic when it is missing, so the consumer can start before orders-service has published anything. */
@Configuration
class KafkaTopics {

    @Bean
    fun orderPlaced(): NewTopic = NewTopic("order-placed", 1, 1.toShort())
}
