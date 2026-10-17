// Decks: how they nest and are ordered, who may do what with them (decks are what's lent), their
// levels, and what studying one offers today.
plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
}

kotlin {
    sourceSets.commonMain.dependencies { api(project(":core:kernel")) }
}
