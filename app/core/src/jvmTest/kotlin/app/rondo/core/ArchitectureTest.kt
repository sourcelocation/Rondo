package app.rondo.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The core's boundaries, read off its sources. Each slice keeps its code in `domain`,
 * `application` and `infrastructure`; a layer sees its own and those below it, in any slice it's
 * built on; and only infrastructure writes things down, with serialization or a format's library.
 * Which slices a slice is built on is its build's to say: Gradle refuses the rest.
 */
class ArchitectureTest {
    /** Below one another: a layer sees those before it. */
    private enum class Layer { DOMAIN, APPLICATION, INFRASTRUCTURE }

    /** One of a slice's main sources. */
    private class Source(val file: File, val slice: String) {
        private val text = file.readText()
        val name: String = "$slice/${file.name}"
        val pkg: String? = PACKAGE.find(text)?.groupValues?.get(1)
        val layer: Layer? = pkg?.let { Regex("^app\\.rondo\\.core\\.$slice\\.(\\w+)$").find(it) }
            ?.let { m -> Layer.entries.firstOrNull { it.name.lowercase() == m.groupValues[1] } }

        /** The core's layers it names, by slice: in its imports and anywhere in its code. */
        val reaches: List<Pair<String, Layer>> = CORE.findAll(text).map { m ->
            m.groupValues[1] to Layer.entries.first { it.name.lowercase() == m.groupValues[2] }
        }.toList()

        /** What it imports from outside the core. */
        val libraries: List<String> = IMPORT.findAll(text).map { it.groupValues[1] }
            .filter { !it.startsWith("app.rondo.core.") }.toList()
    }

    private val core = File(System.getProperty("rondo.core") ?: ".")

    private val sources: List<Source> = core.listFiles().orEmpty()
        .filter { File(it, "build.gradle.kts").isFile }
        .sortedBy { it.name }
        .flatMap { slice ->
            File(slice, "src").listFiles().orEmpty().filter { it.name.endsWith("Main") }
                .flatMap { set -> set.walk().filter { it.extension == "kt" }.map { Source(it, slice.name) }.toList() }
        }

    @Test
    fun theSlicesAreThere() {
        val slices = sources.map { it.slice }.toSet()
        assertEquals(setOf("kernel", "markup", "decks", "notes", "study", "files", "sync", "workspace"), slices)
    }

    @Test
    fun everySourceIsInALayerOfItsSlice() {
        val astray = sources.filter { it.layer == null }.map { "${it.name} (${it.pkg})" }
        assertEquals(emptyList(), astray, "packages are app.rondo.core.<slice>.<domain|application|infrastructure>")
    }

    @Test
    fun aLayerSeesOnlyItsOwnAndThoseBelow() {
        val crossings = sources.filter { it.layer != null }.flatMap { s ->
            s.reaches.filter { (_, layer) -> layer > s.layer!! }.map { (slice, layer) ->
                "${s.name}: ${s.layer!!.name.lowercase()} sees $slice.${layer.name.lowercase()}"
            }
        }.distinct()
        assertEquals(emptyList(), crossings)
    }

    @Test
    fun onlyInfrastructureWritesThingsDown() {
        val written = sources.filter { it.layer != null && it.layer != Layer.INFRASTRUCTURE }.flatMap { s ->
            s.libraries.filter { lib -> PURE.none { lib.startsWith(it) } }.map { "${s.name}: $it" }
        }
        assertEquals(emptyList(), written, "domains and applications use only ${PURE.joinToString()}")
    }

    @Test
    fun theRulesSeeWhatTheyCheck() {
        assertTrue(sources.size > 30, "found ${sources.size} sources under $core")
        assertTrue(
            sources.any {
                it.layer == Layer.INFRASTRUCTURE &&
                    it.libraries.any { l -> l.startsWith("kotlinx.serialization") }
            },
        )
        assertTrue(sources.any { s -> s.reaches.any { it.first != s.slice } })
    }

    private companion object {
        val PACKAGE = Regex("^package\\s+([\\w.]+)", RegexOption.MULTILINE)
        val IMPORT = Regex("^import\\s+([\\w.]+)", RegexOption.MULTILINE)
        val CORE = Regex("\\bapp\\.rondo\\.core\\.(\\w+)\\.(domain|application|infrastructure)\\b")

        /**
         * What a domain or an application may use besides the core: Kotlin's own library, dates and
         * time zones, and the platform's Unicode tables (normalizing isn't in Kotlin's library).
         */
        val PURE = listOf("kotlin.", "kotlinx.datetime.", "java.text.Normalizer")
    }
}
