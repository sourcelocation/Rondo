// Notes and their note types: what a note holds, the cards its type makes of it, how each side is
// laid out, its tags, and how notes are found.
plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":core:decks"))
        api(project(":core:markup"))
    }
}
