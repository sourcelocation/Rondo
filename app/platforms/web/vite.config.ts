import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import tailwind from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig, type Plugin } from "vite";
import { VitePWA } from "vite-plugin-pwa";

/** Keeps the Kotlin client (src/kotlin) built while the dev server runs; builds expect it built. */
function kotlin(): Plugin {
  return {
    name: "kotlin",
    apply: "serve",
    configureServer(server) {
      const gradle = spawn(
        "./gradlew",
        ["--continuous", "--quiet", ":client:jsBrowserDevelopmentLibraryDistribution"],
        {
          cwd: fileURLToPath(new URL("../..", import.meta.url)),
          stdio: "inherit",
        },
      );
      server.httpServer?.on("close", () => gradle.kill());
    },
  };
}

/** Ktor's JS engine imports `ws` only when it runs on Node: the browser never loads it. */
function nodeOnly(): Plugin {
  return { name: "node-only", enforce: "pre", resolveId: (id) => (id === "ws" ? { id, external: true } : null) };
}

/**
 * The app is served under /app, as in production, where the site answers / and sends share links
 * (`/d/…`, `/i/…`) into the app; here the dev server does both.
 */
function underApp(): Plugin {
  return {
    name: "under-app",
    apply: "serve",
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        const url = request.url ?? "/";
        const to = url === "/app" ? "/app/" : /^\/(?:$|\?|[diu]\/|redeem(?:\/|$))/.test(url) ? `/app${url}` : null;
        if (to === null) return next();
        response.writeHead(302, { Location: to }).end();
      });
    },
  };
}

// One origin, as in production: the Go server answers /api, /media and /blobs, the sync server /sync,
// /mcp and its OAuth metadata, Kratos /auth, and Hydra its own OAuth paths.
const go = "http://localhost:23901";
const sync = "http://localhost:23902";
const hydra = "http://localhost:23906";

export default defineConfig({
  base: "/app/",
  plugins: [
    kotlin(),
    nodeOnly(),
    underApp(),
    react(),
    tailwind(),
    // Offline-first, and pushes: src/sw.ts.
    VitePWA({
      strategies: "injectManifest",
      srcDir: "src",
      filename: "sw.ts",
      registerType: "autoUpdate",
      // src/main.tsx registers it.
      injectRegister: false,
      manifest: {
        name: "Rondo",
        short_name: "Rondo",
        theme_color: "#F2EFE7",
        background_color: "#F2EFE7",
        display: "standalone",
        icons: [],
      },
      injectManifest: {
        globPatterns: ["**/*.{js,css,html,wasm,woff2,otf,svg,png}"],
        maximumFileSizeToCacheInBytes: 16 * 1024 * 1024,
      },
      // Pushes arrive in development too; the cache stays out of its way (src/sw.ts).
      devOptions: { enabled: true, type: "module" },
    }),
  ],
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  server: {
    // Rondo's own port (server/compose.yaml): Kratos, Hydra and the servers expect the app here.
    port: 23900,
    strictPort: true,
    // The brand's tokens, fonts and artwork live beside the app.
    fs: { allow: [".", "../../../brand"] },
    proxy: {
      "/auth": {
        target: "http://localhost:23904",
        rewrite: (path) => path.replace(/^\/auth/, ""),
        // Kratos's API flows refuse anything a browser marks (Origin, cookies); they need neither.
        configure: (proxy) =>
          proxy.on("proxyReq", (request) => {
            request.removeHeader("origin");
            request.removeHeader("cookie");
          }),
      },
      "/api": go,
      "/media": go,
      "/blobs": go,
      "/sync": sync,
      "/mcp": sync,
      "/.well-known/oauth-protected-resource": sync,
      "/oauth2": hydra,
      "/userinfo": hydra,
      "/.well-known/openid-configuration": hydra,
      "/.well-known/oauth-authorization-server": hydra,
      "/.well-known/jwks.json": hydra,
    },
  },
  optimizeDeps: { exclude: ["@sqlite.org/sqlite-wasm"] },
  worker: { format: "es" },
});
