# Rondo's docs, served under /docs (Caddyfile), built from the repository root: the API reference
# reads server/api, and the pages the brand's dist/ and fonts/.
# The Node .node-version pins, for CI and development: a change to one is a change to both.
FROM node:26.11.1-alpine AS build
WORKDIR /src
# The workspace's lockfile (pnpm-workspace.yaml), and only the docs' dependencies from it.
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml ./
COPY site/docs/package.json site/docs/
# The pnpm package.json names (packageManager).
RUN npm install --global "$(node -p 'require("./package.json").packageManager')"
RUN pnpm install --frozen-lockfile --filter @rondo/docs
COPY server/api server/api
COPY brand/dist brand/dist
COPY brand/fonts brand/fonts
COPY site/tsconfig.base.json site/
COPY site/docs site/docs
RUN pnpm --dir site/docs build

FROM caddy:2.11.7-alpine
COPY site/docs/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /src/site/docs/out /srv/docs
EXPOSE 8080
