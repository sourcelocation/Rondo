// The sync protocol: what a device and the sync server say to each other, carrying every slice's
// records.
plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
}

kotlin {
    sourceSets.commonMain.dependencies { api(project(":core:study")) }
}
