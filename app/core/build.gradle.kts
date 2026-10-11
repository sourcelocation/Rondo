// Rules, models and the card format: everything every device and the sync server must agree on.
// No I/O. Rondo's data is written here by hand, with its records: what's stored and synced.
plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
}

kotlin {
    jvm()
    js {
        browser { testTask { enabled = false } }
        nodejs()
        binaries.library()
    }
    compilerOptions { optIn.addAll("kotlin.js.ExperimentalJsExport", "kotlin.uuid.ExperimentalUuidApi") }
    sourceSets {
        commonMain {
            dependencies {
                api(libs.serialization.json)
                api(libs.datetime)
                implementation(libs.csv)
            }
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
