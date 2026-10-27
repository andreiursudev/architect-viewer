package io.stackvibed.model

import java.util.Locale

/** Component ids double as file names, so they are slugs matching `^[a-z0-9][a-z0-9-]*$` (no Windows-illegal characters). */
object ComponentIds {
    const val E2E_CLIENT = "e2e-client"

    private val NON_SLUG = Regex("[^a-z0-9]+")

    fun slug(raw: String): String = raw.lowercase(Locale.ROOT).replace(NON_SLUG, "-").trim('-')

    fun service(serviceName: String): String = slug(serviceName)

    fun database(dbSystem: String, dbName: String?): String =
        slug(listOfNotNull(dbSystem, dbName).joinToString("-"))
}
