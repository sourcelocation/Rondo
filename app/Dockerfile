# The sync server (target sync) and the web app (target web), built from the repository root:
# Gradle generates models from server/api, and the web app reads brand/.
FROM eclipse-temurin:27_35-jdk AS gradle
WORKDIR /src
COPY server/api server/api
COPY app app
WORKDIR /src/app
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --quiet :sync:installDist :client:jsBrowserProductionLibraryDistribution

FROM eclipse-temurin:27_35-jre-alpine AS sync
COPY --from=gradle /src/app/sync/build/install/sync /opt/sync
ENV JAVA_TOOL_OPTIONS="-Xmx192m -XX:+UseSerialGC" RONDO_SYNC_PORT=8081
EXPOSE 8081
ENTRYPOINT ["/opt/sync/bin/sync"]

FROM node:26.11.1-alpine AS web-build
WORKDIR /src/app/platforms/web
COPY app/platforms/web/package.json app/platforms/web/pnpm-lock.yaml app/platforms/web/pnpm-workspace.yaml ./
# The pnpm package.json names (packageManager).
RUN npm install --global "$(node -p 'require("./package.json").packageManager')"
RUN pnpm install --frozen-lockfile
COPY brand /src/brand
COPY app/platforms/web ./
COPY --from=gradle /src/app/platforms/web/src/kotlin src/kotlin
ARG SENTRY_DSN=""
ARG RELEASE=dev
RUN VITE_SENTRY_DSN=$SENTRY_DSN VITE_RELEASE=$RELEASE pnpm build

FROM caddy:2.11.7-alpine AS web
COPY app/platforms/web/Caddyfile /etc/caddy/Caddyfile
COPY --from=web-build /src/app/platforms/web/dist /srv
EXPOSE 8080
