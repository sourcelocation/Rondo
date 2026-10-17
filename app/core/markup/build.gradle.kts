// The text of a field: Rondo's markup and what it reads as, and the formats others write it in (the
// editor's documents, the HTML Anki keeps in a field).
plugins {
    alias(libs.plugins.multiplatform)
}

kotlin {
    sourceSets.commonMain.dependencies { implementation(libs.serialization.json) }
}
