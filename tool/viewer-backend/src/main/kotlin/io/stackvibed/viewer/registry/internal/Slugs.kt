package io.stackvibed.viewer.registry.internal

import java.util.Locale

val SLUG: Regex = Regex("^[a-z0-9][a-z0-9-]*$")

private val NOT_SLUG_CHARS = Regex("[^a-z0-9]+")

/** Lowercases [text], turns runs of other characters into `-` and trims `-`; the result may be empty. */
fun slug(text: String): String = text.lowercase(Locale.ROOT).replace(NOT_SLUG_CHARS, "-").trim('-')
