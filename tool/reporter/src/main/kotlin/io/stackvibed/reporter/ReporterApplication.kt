package io.stackvibed.reporter

import io.stackvibed.reporter.otlp.SpanEventMapper
import io.stackvibed.reporter.runs.RecordingWindow
import io.stackvibed.reporter.runs.RunResolver
import io.stackvibed.reporter.store.RecordingStore
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

@SpringBootApplication
@EnableConfigurationProperties(ReporterProperties::class)
class ReporterApplication {
    @Bean fun clock(): Clock = Clock.systemUTC()

    @Bean fun spanEventMapper() = SpanEventMapper()

    @Bean fun recordingWindow() = RecordingWindow()

    @Bean fun runResolver(window: RecordingWindow) = RunResolver(window)

    @Bean fun recordingStore(properties: ReporterProperties, clock: Clock) =
        RecordingStore(properties.recordingsDir, clock, properties.version)
}

fun main(args: Array<String>) {
    runApplication<ReporterApplication>(*args)
}
