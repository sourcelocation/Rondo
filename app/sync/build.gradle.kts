// The sync server: pull, push and fetch over Postgres with :core's rules, and hosted MCP.
plugins {
    alias(libs.plugins.jvm)
    alias(libs.plugins.serialization)
    application
}

application { mainClass = "app.rondo.sync.MainKt" }

dependencies {
    implementation(project(":core"))
    implementation(project(":mcp"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.negotiation)
    implementation(libs.ktor.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.hikari)
    implementation(libs.postgres)
    implementation(libs.logback)
    implementation(libs.sentry)
    testImplementation(kotlin("test"))
    testImplementation(project(":client"))
    testImplementation(libs.ktor.server.test)
    testImplementation(libs.coroutines.test)
}

// `./gradlew :sync:run` uses the compose stack (server/compose.yaml) unless the environment says otherwise.
tasks.named<JavaExec>("run") {
    val development = mapOf(
        "RONDO_DATABASE_URL" to "postgres://rondo:rondo@localhost:23903/rondo?sslmode=disable",
        "RONDO_HYDRA_ADMIN_URL" to "http://localhost:23907",
    )
    development.forEach { (key, value) -> environment(key, System.getenv(key) ?: value) }
}

tasks.test {
    environment(
        "RONDO_TEST_DATABASE_URL",
        System.getenv("RONDO_TEST_DATABASE_URL")
            ?: "jdbc:postgresql://localhost:23903/rondo_test?user=rondo&password=rondo",
    )
    systemProperty("migrations", rootDir.resolve("../server/db/migrations").path)
}
