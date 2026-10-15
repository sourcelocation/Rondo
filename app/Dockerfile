# The sync server (target sync) and the web app (target web), built from the repository root: the
# web app reads brand/.
FROM eclipse-temurin:25.0.4_7-jdk AS gradle
WORKDIR /src
COPY app app
WORKDIR /src/app
# One Gradle run for both targets: built together (docker-bake.hcl), they build it once.
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --quiet :sync:installDist :client:jsBrowserProductionLibraryDistribution

FROM eclipse-temurin:25.0.4_7-jre-alpine AS sync
COPY --from=gradle /src/app/sync/build/install/sync /opt/sync
ENV JAVA_TOOL_OPTIONS="-Xmx192m -XX:+UseSerialGC" RONDO_SYNC_PORT=8081
EXPOSE 8081
ENTRYPOINT ["/opt/sync/bin/sync"]

# The Node .node-version pins, for CI and development: a change to one is a change to both.
FROM node:26.11.1-alpine AS web-build
WORKDIR /src
# The workspace's lockfile (pnpm-workspace.yaml), and only the web app's dependencies from it.
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml ./
COPY app/platforms/web/package.json app/platforms/web/
# The pnpm package.json names (packageManager).
RUN npm install --global "$(node -p 'require("./package.json").packageManager')"
RUN pnpm install --frozen-lockfile --filter rondo-web
COPY brand brand
COPY app/platforms/web app/platforms/web
COPY --from=gradle /src/app/platforms/web/src/kotlin app/platforms/web/src/kotlin
ARG SENTRY_DSN=""
RUN VITE_SENTRY_DSN=$SENTRY_DSN pnpm --dir app/platforms/web build

FROM caddy:2.11.7-alpine AS web
COPY app/platforms/web/Caddyfile /etc/caddy/Caddyfile
COPY --from=web-build /src/app/platforms/web/dist /srv
EXPOSE 8080
