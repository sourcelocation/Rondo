# rondo.matthewsource.com's static site (the landing and the docs), built from the repository root:
# the docs' API reference reads server/api, and both read the brand's artwork and fonts.
FROM node:26-alpine AS build
RUN npm install --global pnpm@11.22.0
WORKDIR /src/site
COPY site/package.json site/pnpm-lock.yaml site/pnpm-workspace.yaml ./
COPY site/landing/package.json landing/
COPY site/docs/package.json docs/
RUN pnpm install --frozen-lockfile
COPY server/api /src/server/api
COPY brand/dist /src/brand/dist
COPY brand/fonts /src/brand/fonts
COPY site ./
RUN pnpm build

FROM caddy:2-alpine
COPY site/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /src/site/landing/dist /srv/landing
COPY --from=build /src/site/docs/out /srv/docs
EXPOSE 8080
