// rondo-mcp: Rondo's MCP tools over stdio, for agents on this computer. It keeps its own copy of
// your data in ~/.rondo and syncs like any other device.
plugins {
    alias(libs.plugins.jvm)
    application
}

application {
    mainClass = "app.rondo.cli.MainKt"
    applicationName = "rondo-mcp"
}

dependencies {
    implementation(project(":client"))
    implementation(project(":mcp"))
    implementation(libs.coroutines.core)
}
