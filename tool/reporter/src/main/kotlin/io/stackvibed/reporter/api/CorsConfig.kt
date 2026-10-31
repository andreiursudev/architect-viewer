package io.stackvibed.reporter.api

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * The browser package exports spans straight from the page, so the OTLP endpoint answers CORS for pages served from this
 * machine. The recording control API stays closed to browsers.
 */
@Configuration
class CorsConfig : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/v1/traces")
            .allowedOriginPatterns("http://localhost:[*]", "http://127.0.0.1:[*]")
            .allowedMethods("POST")
            .allowedHeaders("Content-Type")
    }
}
