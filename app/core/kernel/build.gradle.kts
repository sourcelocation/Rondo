// The shared kernel: what every slice speaks. Why a change is refused, the clocks and ids rows are
// stamped with, places among siblings, and the record every stored row is.
plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
}

kotlin {
    sourceSets.commonMain.dependencies { api(libs.serialization.json) }
}
