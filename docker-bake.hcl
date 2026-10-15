# Rondo's images, built from the repository root in one build: `docker buildx bake` builds them all,
# `docker buildx bake sync web` some. Built together, the sync server and the web app share
# app/Dockerfile's Gradle stage. Releases build and push them all (.github/workflows/release.yml).

variable "REGISTRY" { default = "ghcr.io/sourcelocation" }
# The images' tag: the release's. The version they report is their source's (release-please-config.json).
variable "TAG" { default = "dev" }
variable "REVISION" { default = "" }
variable "SENTRY_DSN" { default = "" }
# Releases write the cache every build reads; other builds only read it.
variable "WRITE_CACHE" { default = "" }

group "default" { targets = ["image"] }

target "image" {
  name = item.name
  matrix = {
    item = [
      { name = "api", dockerfile = "server/Dockerfile", target = "" },
      { name = "sync", dockerfile = "app/Dockerfile", target = "sync" },
      { name = "web", dockerfile = "app/Dockerfile", target = "web" },
      { name = "landing", dockerfile = "site/landing/Dockerfile", target = "" },
      { name = "docs", dockerfile = "site/docs/Dockerfile", target = "" },
    ]
  }
  context = "."
  dockerfile = item.dockerfile
  target = item.target
  tags = ["${REGISTRY}/rondo-${item.name}:${TAG}"]
  labels = {
    "org.opencontainers.image.version" = TAG
    "org.opencontainers.image.revision" = REVISION
  }
  args = { SENTRY_DSN = SENTRY_DSN }
  cache-from = ["type=registry,ref=${REGISTRY}/rondo-${item.name}:buildcache"]
  cache-to = WRITE_CACHE == "true" ? ["type=registry,ref=${REGISTRY}/rondo-${item.name}:buildcache,mode=max"] : []
}
