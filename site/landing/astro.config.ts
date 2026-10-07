import { defineConfig, envField } from "astro/config";

/**
 * rondo.matthewsource.com's front page, built as static files that the site image serves at / (site/Caddyfile).
 * Until the real landing page, it is links: to the web app, the documentation and the legal pages.
 */
export default defineConfig({
  site: "https://rondo.matthewsource.com",
  server: { port: 23911 },
  env: {
    schema: {
      // .env.development and .env.production
      PUBLIC_RONDO_APP_URL: envField.string({ context: "client", access: "public", url: true }),
      PUBLIC_RONDO_DOCS_URL: envField.string({ context: "client", access: "public", url: true }),
    },
  },
});
