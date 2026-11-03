package io.stackvibed.sandbox.orders

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Creates the topic when it is missing, so the first order does not depend on who started first. */
@Configuration
class KafkaTopics {

    @Bean
    fun orderPlaced(): NewTopic = NewTopic("order-placed", 1, 1.toShort())
}
