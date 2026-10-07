import "./index.css";
import * as Sentry from "@sentry/react";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router";
import { registerSW } from "virtual:pwa-register";
import { App } from "./App";
import { OtherTab, Splash } from "./components/kit";
import { boot } from "./rondo";

if (import.meta.env.VITE_SENTRY_DSN)
  Sentry.init({ dsn: import.meta.env.VITE_SENTRY_DSN, release: import.meta.env.VITE_RELEASE });

// The service worker (src/sw.ts): the app offline, and pushes. A new version takes over on its own.
registerSW({ immediate: true });

const root = createRoot(document.getElementById("root")!);

// One tab at a time: the database file in OPFS can't be shared. The tab holding the lock keeps it
// until another takes over, which makes this one reload, freeing the file, and offer it back.
function open(takeOver: boolean) {
  root.render(<Splash />);
  navigator.locks
    .request("rondo", takeOver ? { steal: true } : { ifAvailable: true }, async (lock) => {
      if (!lock) return root.render(<OtherTab onUseHere={() => open(true)} />);
      await boot();
      root.render(
        <StrictMode>
          <BrowserRouter basename={import.meta.env.BASE_URL.replace(/\/$/, "")}>
            <App />
          </BrowserRouter>
        </StrictMode>,
      );
      await new Promise(() => {});
    })
    .catch((e: unknown) =>
      e instanceof DOMException && e.name === "AbortError" ? location.reload() : Promise.reject(e),
    );
}

open(false);
