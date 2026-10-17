import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// The core: everything every device and the sync server must agree on, and no I/O. It's cut into
// vertical slices, a subproject each, every one seeing only the slices it's built on:
//
//   kernel     refusals, clocks, ids, places among siblings; the record every stored row is
//   markup     the text of a field, and the formats others write it in
//   decks      decks, who may do what with them, their levels and today's counts
//   notes      notes and note types, their cards' layout, tags, and finding notes
//   study      cards' histories, FSRS, the learner's days, settings and smart decks
//   files      decks as files: Rondo's own, and a spreadsheet's rows made notes
//   sync       the sync protocol, carrying every slice's records
//   workspace  one account's data as agents work with it
//
// Inside a slice, `domain` holds its things and their rules; `application` what's done with them
// (ports apps and servers implement, and the rules of a use case); `infrastructure` how they're
// written down: records, SQL, file formats, wire messages. A domain sees only domains, an
// application domains and applications, and only infrastructure uses serialization or a format's
// library. ArchitectureTest keeps it so. This project is every slice at once, for what's built on them.
plugins {
    alias(libs.plugins.multiplatform)
}

/** For the JVM (the servers, the command line) and JavaScript (the web app), tested on both. */
fun KotlinMultiplatformExtension.targets() {
    jvm()
    js {
        browser { testTask { enabled = false } }
        nodejs()
    }
    compilerOptions { optIn.addAll("kotlin.js.ExperimentalJsExport", "kotlin.uuid.ExperimentalUuidApi") }
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") { configure<KotlinMultiplatformExtension> { targets() } }
}

val slices = listOf("kernel", "markup", "decks", "notes", "study", "files", "sync", "workspace")

kotlin {
    targets()
    sourceSets.commonMain.dependencies { slices.forEach { api(project(":core:$it")) } }
}

// `:core:allTests` tests every slice, and ArchitectureTest reads their sources.
tasks.named("allTests") { dependsOn(slices.map { ":core:$it:allTests" }) }
tasks.named<Test>("jvmTest") {
    inputs.files(slices.map { layout.projectDirectory.dir("$it/src") }).withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("slices")
    systemProperty("rondo.core", layout.projectDirectory.asFile.path)
}
