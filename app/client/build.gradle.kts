// Everything a device runs: SQLite, sync, sign-in, studying, Anki import and the screens' state.
// The HTTP client is generated from the API spec.
plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.openapi)
}

val api = layout.buildDirectory.dir("openapi")

openApiGenerate {
    generatorName = "kotlin"
    this.library.set("multiplatform")
    inputSpec = rootDir.resolve("../server/api/rondo.yaml").path
    outputDir = api.get().asFile.path
    packageName = "app.rondo.client.api"
    modelPackage = "app.rondo.core.model"
    apiPackage = "app.rondo.client.api"
    globalProperties = mapOf("apis" to "", "supportingFiles" to "", "apiDocs" to "false", "apiTests" to "false")
    // Webhooks are for the stores, not for apps.
    openapiNormalizer = mapOf("FILTER" to "tag:api|sync")
    configOptions = mapOf("dateLibrary" to "kotlinx-datetime", "useTags" to "true")
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
            kotlin.srcDir(api.map { it.dir("src/commonMain/kotlin") })
            dependencies {
                api(project(":core"))
                implementation(libs.coroutines.core)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.negotiation)
                implementation(libs.ktor.json)
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

tasks.matching {
    it.name.startsWith("compile") || it.name.endsWith("SourcesJar")
}.configureEach { dependsOn("openApiGenerate") }
