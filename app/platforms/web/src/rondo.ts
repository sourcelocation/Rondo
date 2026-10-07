import { useCallback, useEffect, useState, useSyncExternalStore } from "react";
import { media, start, strings, type App } from "./kotlin/rondo-client.mjs";

export * from "./kotlin/rondo-client.mjs";

/** Every word the app shows. */
export const s = strings.get();

// Kratos is on the app's origin, under /auth: its API flows refuse requests a browser marks with
// Origin or cookies, so the proxy in front of it (Vite here, Traefik in production) drops them.
export const kratos = `${location.origin}/auth`;

export let app: App;

export async function boot(): Promise<App> {
  app = await start(
    location.origin,
    kratos,
    () => new Worker(new URL("./db.worker.ts", import.meta.url), { type: "module" }),
  );
  return app;
}

type Screen = { readonly state: unknown; watch(watcher: () => void): () => void; close(): void };

/**
 * A Kotlin screen while the component shows it: its state (null until loaded) and the screen for
 * actions, which are safe to call once the state is there.
 */
export function useScreen<T extends Screen>(make: () => T, deps: unknown[] = []): [NonNullable<T["state"]> | null, T] {
  const [screen, setScreen] = useState<T | null>(null);
  useEffect(() => {
    const opened = make();
    setScreen(opened);
    return () => opened.close();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  const subscribe = useCallback((changed: () => void) => screen?.watch(changed) ?? (() => {}), [screen]);
  const state = useSyncExternalStore(subscribe, () => (screen?.state ?? null) as NonNullable<T["state"]> | null);
  return [state, screen as T];
}

/** The app-wide state: account, sync and preferences. */
export function useApp() {
  const subscribe = useCallback((changed: () => void) => app.watch(changed), []);
  return useSyncExternalStore(subscribe, () => app.state ?? null);
}

/** Applies the theme chosen in Settings as data-theme, following the system's while it's "system". */
export function useTheme() {
  const theme = (["system", "light", "dark"] as const)[useApp()?.theme ?? 0] ?? "system";
  useEffect(() => {
    const dark = matchMedia("(prefers-color-scheme: dark)");
    const apply = () =>
      (document.documentElement.dataset.theme = theme === "system" ? (dark.matches ? "dark" : "light") : theme);
    apply();
    dark.addEventListener("change", apply);
    return () => dark.removeEventListener("change", apply);
  }, [theme]);
  return theme;
}

const urls = new Map<string, Promise<string | null>>();

/** A media file's URL, once it's on this device. */
export function useMedia(hash: string | null | undefined): string | undefined {
  const [url, setUrl] = useState<string>();
  useEffect(() => {
    if (!hash) return;
    let live = true;
    let found = urls.get(hash);
    if (!found) urls.set(hash, (found = media(app, hash).then((u) => (u ? u : (urls.delete(hash), null)))));
    found.then((u) => live && setUrl(u ?? undefined));
    return () => {
      live = false;
    };
  }, [hash]);
  return url;
}

/** A language's name in the reader's language. */
export const languageName = (code: string) =>
  new Intl.DisplayNames([navigator.language], { type: "language" }).of(code) ?? code;

/** A date as the reader writes it. */
export const date = (iso: string | number) =>
  new Date(iso).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
