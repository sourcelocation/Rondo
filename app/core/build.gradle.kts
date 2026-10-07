// Rules, models and the card format: everything every device and the sync server must agree on.
// No I/O. The models are generated from the API spec.
plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
    alias(libs.plugins.openapi)
}

val generated = layout.buildDirectory.dir("openapi")

openApiGenerate {
    generatorName = "kotlin"
    this.library.set("multiplatform")
    inputSpec = rootDir.resolve("../server/api/rondo.yaml").path
    outputDir = generated.get().asFile.path
    packageName = "app.rondo.core.api"
    modelPackage = "app.rondo.core.model"
    globalProperties = mapOf("models" to "", "modelDocs" to "false", "modelTests" to "false")
    configOptions = mapOf("dateLibrary" to "kotlinx-datetime", "enumPropertyNaming" to "UPPERCASE")
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
            kotlin.srcDir(generated.map { it.dir("src/commonMain/kotlin") })
            dependencies {
                api(libs.serialization.json)
                api(libs.datetime)
            }
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

tasks.matching {
    it.name.startsWith("compile") || it.name.endsWith("SourcesJar")
}.configureEach { dependsOn("openApiGenerate") }
