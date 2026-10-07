// MCP tools over a Workspace: hosted by the sync server, local in the command-line client.
plugins {
    alias(libs.plugins.jvm)
    alias(libs.plugins.serialization)
}

dependencies {
    api(project(":core"))
    api(libs.mcp)
}
