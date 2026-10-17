// Decks as files: a deck in hand with what's in it, what a file must do, the rows a spreadsheet's
// notes are made from, and the formats themselves (Rondo's own, CSV and TSV).
plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":core:study"))
        implementation(libs.csv)
    }
}
