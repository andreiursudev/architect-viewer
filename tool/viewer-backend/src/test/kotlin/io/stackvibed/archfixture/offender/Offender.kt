package io.stackvibed.archfixture.offender

import io.stackvibed.archfixture.web.WebThing

/** Test fixture for ArchitectureTest: a module class that wrongly depends on `web`. */
class Offender {
    fun web() = WebThing()
}
