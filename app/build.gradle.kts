import com.diffplug.spotless.LineEnding

plugins {
    alias(libs.plugins.spotless)
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.jvm) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.openapi) apply false
}

// Kotlin/JS gets Node.js from the repository declared in settings.gradle.kts, the only place
// repositories are declared.
allprojects {
    plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
        the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().downloadBaseUrl = null
    }
}

// JVM 17 bytecode everywhere: the servers and the command-line client run on any newer JDK.
allprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
    tasks.withType<JavaCompile>().configureEach { options.release = 17 }
}

// ktlint, in IntelliJ IDEA's style with the repository's .editorconfig: `./gradlew spotlessApply`
// formats, CI runs spotlessCheck. Generated code stays as its generators write it.
spotless {
    lineEndings = LineEnding.UNIX
    kotlin {
        target(
            fileTree(".") {
                include("*/src/**/*.kt")
                exclude("**/build/**")
            },
        )
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target(fileTree(".") { include("*.gradle.kts", "*/*.gradle.kts") })
        ktlint(libs.versions.ktlint.get())
    }
}
