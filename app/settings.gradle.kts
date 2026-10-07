// The app's Gradle build: the shared Kotlin (core, client, mcp), the sync server, the command-line
// MCP client and, later, the Android app. It reads ../server/api (the API spec) and ../brand.
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
include(":core", ":client", ":mcp", ":cli", ":sync")
