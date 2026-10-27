package io.stackvibed.viewer.web

import io.stackvibed.viewer.Fixtures
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.nio.file.Files
import java.nio.file.Path

@SpringBootTest
@AutoConfigureMockMvc
class ApiTest {
    @Autowired
    lateinit var mvc: MockMvc

    companion object {
        private val root: Path = Files.createTempDirectory("viewer-api-test")

        @JvmStatic
        @DynamicPropertySource
        fun paths(registry: DynamicPropertyRegistry) {
            val recordings = root.resolve("recordings")
            Fixtures.copyAll(recordings)
            registry.add("workspace") { root.resolve("stack").toString() }
            registry.add("recordings") { recordings.toString() }
        }
    }

    /** Every test starts with an empty workspace; the stores read the folder on each call. */
    @BeforeEach
    fun cleanWorkspace() {
        root.resolve("stack").toFile().deleteRecursively()
    }

    @Test
    fun `workspace view endpoint`() {
        mvc.get("/api/workspace").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.components[*].id") { value(org.hamcrest.Matchers.hasItem("orders-service")) }
            jsonPath("$.components[?(@.id=='e2e-client')].type") { value("client") }
            jsonPath("$.connections[?(@.id=='e2e-client--orders-service')].state") { value("discovered") }
            jsonPath("$.scenarios[?(@.id=='create-order')].latestRunId") { value("run-2") }
        }
    }

    @Test
    fun `accepting a connection persists it`() {
        mvc.put("/api/connections/e2e-client--orders-service") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"from":"e2e-client","to":"orders-service","kind":"http","label":"POST /orders","state":"accepted"}"""
        }.andExpect { status { isNoContent() } }

        mvc.get("/api/workspace").andExpect {
            jsonPath("$.connections[?(@.id=='e2e-client--orders-service')].state") { value("accepted") }
            jsonPath("$.connections[?(@.id=='e2e-client--orders-service')].unverified") { value(false) }
        }
    }

    @Test
    fun `accepting a component persists it`() {
        mvc.put("/api/components/billing-service") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"id":"billing-service","type":"service","displayName":"Billing","tags":["core"]}"""
        }.andExpect { status { isNoContent() } }

        mvc.get("/api/workspace").andExpect {
            jsonPath("$.components[?(@.id=='billing-service')].displayName") { value("Billing") }
            jsonPath("$.components[?(@.id=='billing-service')].discovered") { value(false) }
        }
    }

    @Test
    fun `component id must match the path`() {
        mvc.put("/api/components/billing-service") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"id":"other","type":"service","displayName":"Billing"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `discovered state is rejected`() {
        mvc.put("/api/connections/a--b") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"from":"a","to":"b","kind":"http","label":null,"state":"discovered"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `connection id must be from--to`() {
        mvc.put("/api/connections/a--c") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"from":"a","to":"b","kind":"http","label":null,"state":"manual"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `notes round trip as markdown`() {
        mvc.get("/api/notes/orders-service").andExpect { status { isNotFound() } }

        mvc.put("/api/notes/orders-service") {
            contentType = MediaType.valueOf("text/markdown")
            content = "# Orders\n\nowned by team a"
        }.andExpect { status { isNoContent() } }

        mvc.get("/api/notes/orders-service").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("text/markdown") }
            content { string("# Orders\n\nowned by team a") }
        }
    }

    @Test
    fun `a note can be cleared with an empty body`() {
        mvc.put("/api/notes/orders-service") {
            contentType = MediaType.valueOf("text/markdown")
            content = "to be removed"
        }.andExpect { status { isNoContent() } }

        mvc.put("/api/notes/orders-service") {
            contentType = MediaType.valueOf("text/markdown")
        }.andExpect { status { isNoContent() } }

        mvc.get("/api/notes/orders-service").andExpect {
            status { isOk() }
            content { string("") }
        }
    }

    @Test
    fun `notes keep non-ascii text`() {
        mvc.put("/api/notes/billing-service") {
            contentType = MediaType.valueOf("text/markdown;charset=UTF-8")
            content = "caf\u00e9 \u2713".toByteArray(Charsets.UTF_8)
        }.andExpect { status { isNoContent() } }

        val body = mvc.get("/api/notes/billing-service").andExpect { status { isOk() } }.andReturn().response
        String(body.contentAsByteArray, Charsets.UTF_8) shouldBe "caf\u00e9 \u2713"
    }

    @Test
    fun `layout round trips and is 404 until saved`() {
        mvc.get("/api/boards/main/layout").andExpect { status { isNotFound() } }

        mvc.put("/api/boards/main/layout") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nodes":{"orders-service":{"x":10.0,"y":20.0}},"viewport":{"x":1.0,"y":2.0,"zoom":1.5}}"""
        }.andExpect { status { isNoContent() } }

        mvc.get("/api/boards/main/layout").andExpect {
            status { isOk() }
            jsonPath("$.nodes.orders-service.x") { value(10.0) }
            jsonPath("$.viewport.zoom") { value(1.5) }
        }
    }

    @Test
    fun `invalid note target is a bad request`() {
        mvc.put("/api/notes/Not_A_Slug") {
            contentType = MediaType.valueOf("text/markdown")
            content = "x"
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `runs of a scenario are listed newest first`() {
        mvc.get("/api/scenarios/create-order/runs").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].runId") { value("run-2") }
            jsonPath("$[1].runId") { value("run-1") }
            jsonPath("$[0].kind") { value("e2e") }
        }
        mvc.get("/api/scenarios/never-ran/runs").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
    }

    @Test
    fun `flow endpoint returns the steps of a run`() {
        mvc.get("/api/runs/run-1/flow").andExpect {
            status { isOk() }
            jsonPath("$.runId") { value("run-1") }
            jsonPath("$.steps.length()") { value(2) }
            jsonPath("$.steps[0].connectionId") { value("e2e-client--orders-service") }
            jsonPath("$.steps[0].kind") { value("http") }
            jsonPath("$.steps[0].event.type") { value("http.server") }
            jsonPath("$.steps[1].parentStepId") { value("0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331") }
        }
    }

    @Test
    fun `flow endpoint returns 404 for unknown run`() {
        mvc.get("/api/runs/nope/flow").andExpect { status { isNotFound() } }
    }
}
