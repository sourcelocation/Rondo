// The app's Gradle build: the shared Kotlin (core, client, mcp), the sync server, the command-line
// MCP client and, later, the Android app. It reads nothing outside app/.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        google()
        // The Node.js that Kotlin/JS runs tests with.
        ivy("https://nodejs.org/dist") {
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
    }
}

rootProject.name = "rondo"
// The core's slices (core/build.gradle.kts), then what's built on them.
include(":core", ":core:kernel", ":core:markup", ":core:decks", ":core:notes", ":core:study", ":core:files")
include(":core:sync", ":core:workspace")
include(":client", ":mcp", ":cli", ":sync")
