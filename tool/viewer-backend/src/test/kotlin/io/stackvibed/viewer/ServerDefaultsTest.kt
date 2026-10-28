package io.stackvibed.viewer

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment

/** The viewer listens on 8090 (8080 is the usual port of the services it looks at) and on the loopback interface only. */
@SpringBootTest
class ServerDefaultsTest {
    @Autowired
    lateinit var environment: Environment

    @Test
    fun `default port is 8090`() {
        environment.getProperty("server.port") shouldBe "8090"
    }

    @Test
    fun `binds to loopback only`() {
        environment.getProperty("server.address") shouldBe "127.0.0.1"
    }
}
