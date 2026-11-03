package io.stackvibed.fixture

import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.serializer.JacksonJsonSerializer
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit.SECONDS

/**
 * Kafka for the agent tests, only under the `kafka` profile (the broker's address comes from
 * `spring.kafka.bootstrap-servers`). `fixture-events` carries a JSON value produced from a map and consumed as a
 * String through Boot's default listener container; `fixture-bytes` carries raw bytes and has no listener.
 */
@Configuration
@Profile("kafka")
class KafkaFixtureConfig {

    @Bean
    fun fixtureEventsTopic() = NewTopic(EVENTS, 1, 1)

    @Bean
    fun fixtureBytesTopic() = NewTopic(BYTES, 1, 1)

    @Bean
    fun jsonKafkaTemplate(properties: KafkaProperties): KafkaTemplate<String, Any> =
        KafkaTemplate(DefaultKafkaProducerFactory(properties.buildProducerProperties(), StringSerializer(), JacksonJsonSerializer<Any>()))

    @Bean
    fun bytesKafkaTemplate(properties: KafkaProperties): KafkaTemplate<String, ByteArray> =
        KafkaTemplate(DefaultKafkaProducerFactory(properties.buildProducerProperties(), StringSerializer(), ByteArraySerializer()))

    companion object {
        const val EVENTS = "fixture-events"
        const val BYTES = "fixture-bytes"
    }
}

@RestController
@Profile("kafka")
class KafkaController(
    private val jsonKafkaTemplate: KafkaTemplate<String, Any>,
    private val bytesKafkaTemplate: KafkaTemplate<String, ByteArray>,
) {

    class Event(val name: String)

    @PostMapping("/kafka/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun events(@RequestBody event: Event) {
        jsonKafkaTemplate.send(KafkaFixtureConfig.EVENTS, event.name, mapOf("name" to event.name)).get(5, SECONDS)
    }

    @PostMapping("/kafka/bytes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun bytes() {
        bytesKafkaTemplate.send(KafkaFixtureConfig.BYTES, "hello".toByteArray()).get(5, SECONDS)
    }
}

/** Keeps every `fixture-events` value it receives (deserialized as a String by Boot's default consumer). */
@Component
@Profile("kafka")
class FixtureEventsListener {
    val received: MutableList<String> = CopyOnWriteArrayList()

    @KafkaListener(topics = [KafkaFixtureConfig.EVENTS], groupId = "fixture")
    fun onEvent(value: String) {
        received += value
    }
}
