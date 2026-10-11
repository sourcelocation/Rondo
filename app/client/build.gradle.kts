// Everything a device runs: SQLite, sync, sign-in, studying, Anki import and the screens' state.
// Calls to the Go server are written by hand, each beside the service that makes it.
plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases.create("Database") {
        packageName = "app.rondo.client.db"
        generateAsync = true
    }
}

kotlin {
    jvm()
    js {
        browser {
            testTask { enabled = false }
            // Inside the web app, so the npm packages the client imports resolve from its node_modules.
            distribution { outputDirectory = rootDir.resolve("platforms/web/src/kotlin") }
        }
        // Tests run on Node: the code is the browser's, minus the browser.
        nodejs()
        useEsModules()
        binaries.library()
        generateTypeScriptDefinitions()
    }
    compilerOptions { optIn.addAll("kotlin.js.ExperimentalJsExport", "kotlin.uuid.ExperimentalUuidApi") }
    sourceSets {
        commonMain {
            dependencies {
                api(project(":core"))
                implementation(libs.coroutines.core)
                implementation(libs.ktor.client.core)
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines)
                implementation(libs.sqldelight.async)
                implementation(libs.ksoup)
                implementation(libs.serialization.protobuf)
                implementation(libs.okio)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.sqldelight.jvm)
            implementation(libs.zstd)
        }
        jsMain.dependencies {
            implementation(libs.ktor.client.js)
            implementation(libs.sqldelight.web)
            // For Kotlin's own test runs; the web app pins the same versions in its package.json.
            implementation(npm("fflate", "0.8.3"))
            implementation(npm("fzstd", "0.1.1"))
            implementation(npm("@js-joda/timezone", "2.23.0"))
        }
    }
}
