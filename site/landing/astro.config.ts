import { defineConfig, envField } from "astro/config";

/**
 * rondo.matthewsource.com's front page, built as static files that the site image serves at / (site/Caddyfile):
 * the landing page, the legal pages and the files that let the apps open Rondo's links.
 */
export default defineConfig({
  site: "https://rondo.matthewsource.com",
  server: { port: 23911 },
  // The Caddyfile's Content-Security-Policy allows only the site's own files: no inline scripts or styles and no
  // data: URLs. So nothing is inlined into the pages, however small.
  build: { inlineStylesheets: "never" },
  vite: {
    build: { assetsInlineLimit: 0 },
    server: {
      // The launch list's sign-ups go to the Go server, which the ingress puts under /api on the same host.
      proxy: { "/api": "http://localhost:23901" },
      // The brand's fonts live beside the site.
      fs: { allow: ["..", "../../brand"] },
    },
  },
  env: {
    schema: {
      // .env.development and .env.production
      PUBLIC_RONDO_APP_URL: envField.string({ context: "client", access: "public", url: true }),
      PUBLIC_RONDO_DOCS_URL: envField.string({ context: "client", access: "public", url: true }),
      // Whether the site shows the apps or the launch list: by the clock ("auto"), or held at "pre" or "live".
      PUBLIC_RONDO_RELEASE: envField.enum({
        context: "client",
        access: "public",
        values: ["auto", "pre", "live"],
        default: "auto",
      }),
    },
  },
});
