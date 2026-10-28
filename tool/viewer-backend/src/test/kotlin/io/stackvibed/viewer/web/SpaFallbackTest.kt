package io.stackvibed.viewer.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.nio.file.Files

/** A reload of a client-side route (`/sequence/<runId>`) must serve the app; the API and missing files stay 404. */
@SpringBootTest
@AutoConfigureMockMvc
class SpaFallbackTest {
    @Autowired
    lateinit var mvc: MockMvc

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun paths(registry: DynamicPropertyRegistry) {
            val root = Files.createTempDirectory("viewer-spa-test")
            registry.add("workspace") { root.resolve("stack").toString() }
            registry.add("recordings") { root.resolve("recordings").toString() }
        }
    }

    @Test
    fun `a client route forwards to index html`() {
        mvc.get("/sequence/run-1").andExpect { forwardedUrl("/index.html") }
    }

    @Test
    fun `a nested client route forwards to index html`() {
        mvc.get("/a/b/c").andExpect { forwardedUrl("/index.html") }
    }

    @Test
    fun `an unknown api path is still 404`() {
        mvc.get("/api/unknown").andExpect { status { isNotFound() } }
    }

    @Test
    fun `a missing file is still 404`() {
        mvc.get("/assets/missing.js").andExpect { status { isNotFound() } }
    }
}
