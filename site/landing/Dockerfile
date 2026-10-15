# rondo.matthewsource.com's own pages, served at / (Caddyfile), built from the repository root: they
# read the brand's dist/ and fonts/.
# The Node .node-version pins, for CI and development: a change to one is a change to both.
FROM node:26.11.1-alpine AS build
WORKDIR /src
# The workspace's lockfile (pnpm-workspace.yaml), and only the landing's dependencies from it.
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml ./
COPY site/landing/package.json site/landing/
# The pnpm package.json names (packageManager).
RUN npm install --global "$(node -p 'require("./package.json").packageManager')"
RUN pnpm install --frozen-lockfile --filter @rondo/landing
COPY brand/dist brand/dist
COPY brand/fonts brand/fonts
COPY site/tsconfig.base.json site/
COPY site/landing site/landing
RUN pnpm --dir site/landing build

FROM caddy:2.11.7-alpine
COPY site/landing/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /src/site/landing/dist /srv
EXPOSE 8080
