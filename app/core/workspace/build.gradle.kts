// One account's data as agents work with it, wherever it lives: a device's copy or the server's.
plugins {
    alias(libs.plugins.multiplatform)
}

kotlin {
    sourceSets.commonMain.dependencies { api(project(":core:study")) }
}
