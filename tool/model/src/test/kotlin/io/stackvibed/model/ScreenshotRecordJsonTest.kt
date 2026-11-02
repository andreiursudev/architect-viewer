package io.stackvibed.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.readValue
import java.time.Instant

class ScreenshotRecordJsonTest {
    private val mapper = jacksonMapperBuilder().build()

    @Test
    fun `screenshot record round-trips`() {
        val json = """{"id":"04b7ffff7cd2b57d","file":"screenshots/04b7ffff7cd2b57d.png","spanIds":["04b7ffff7cd2b57d","d6c8a8bdd007c68c"],"takenAt":"2026-11-02T10:00:00Z"}"""
        val record = ScreenshotRecord(
            id = "04b7ffff7cd2b57d",
            file = ScreenshotRecord.fileOf("04b7ffff7cd2b57d"),
            spanIds = listOf("04b7ffff7cd2b57d", "d6c8a8bdd007c68c"),
            takenAt = Instant.parse("2026-11-02T10:00:00Z"),
        )

        mapper.writeValueAsString(record) shouldBe json
        mapper.readValue<ScreenshotRecord>(json) shouldBe record
    }

    @Test
    fun `screenshot ids are span ids only`() {
        ScreenshotRecord.ID.matches("04b7ffff7cd2b57d") shouldBe true
        for (id in listOf("04B7FFFF7CD2B57D", "04b7ffff7cd2b57", "../manifest", "04b7ffff7cd2b57d.png")) {
            ScreenshotRecord.ID.matches(id) shouldBe false
        }
    }
}
