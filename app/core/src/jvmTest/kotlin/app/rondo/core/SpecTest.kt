package app.rondo.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the API spec and this module must say alike. */
class SpecTest {
    private val spec = File("../../server/api/rondo.yaml").readLines()

    @Test
    fun errorCodesAreTheSpecs() {
        val start = spec.indexOfFirst { it.trim() == "ErrorCode:" }
        val values = spec.drop(start).dropWhile { it.trim() != "enum:" }.drop(1)
            .takeWhile { it.trim().startsWith("- ") }.map { it.trim().removePrefix("- ") }
        assertEquals(values, ErrorCode.entries.map { it.value })
    }
}
