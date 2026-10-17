// Studying: each card's history and where it stands, FSRS's scheduling, the learner's days, their
// settings and their smart decks.
plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":core:notes"))
        api(libs.datetime)
    }
}
