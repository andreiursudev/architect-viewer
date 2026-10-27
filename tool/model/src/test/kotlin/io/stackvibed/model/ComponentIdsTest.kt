package io.stackvibed.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ComponentIdsTest {
    @Test
    fun `service names become slugs`() {
        ComponentIds.service("Orders.Service") shouldBe "orders-service"
    }

    @Test
    fun `database id joins system and name`() {
        ComponentIds.database("postgresql", "orders") shouldBe "postgresql-orders"
        ComponentIds.database("h2", null) shouldBe "h2"
    }

    @Test
    fun `slug strips windows illegal characters`() {
        ComponentIds.slug("db:orders/main?") shouldBe "db-orders-main"
    }

    @Test
    fun `slug collapses runs and trims dashes`() {
        ComponentIds.slug("--My  Service__v2--") shouldBe "my-service-v2"
    }

    @Test
    fun `e2e client id is a valid slug`() {
        ComponentIds.slug(ComponentIds.E2E_CLIENT) shouldBe ComponentIds.E2E_CLIENT
    }
}
