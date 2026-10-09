import { useEffect } from "react";
import { useLocation, useNavigate, useNavigationType, useSearchParams, type Location } from "react-router";

/**
 * How the app's addresses relate: every page has one level above it, overlays sit on the page that
 * opened them (in its search params), and each place in the sidebar, tab bar or account menu
 * remembers where it was left. Up (← and Esc) goes one level up, retracing history when that's
 * where you came from.
 */

const at = (l: Pick<Location, "pathname" | "search">) => l.pathname + l.search;

/** This tab's history inside the app, as the router moved through it, with the place each page was in. */
const visited: { key: string; url: string; place: string | null }[] = [];

/** The places: roots of the sidebar, the tab bar and the account menu. Each remembers where it was left. */
export const places = ["/", "/browse", "/discover", "/progress", "/settings", "/staff"] as const;
const lastIn = new Map<string, string>();

/**
 * Pages in no place of their own, which take the place they were opened from: the ones anyone can
 * link to (profiles, deck previews, invitations), and signing in, which returns to where it began.
 */
const borrows = (path: string) => /^\/(u|d|i)\//.test(path) || path === "/sign-in" || path === "/oauth/consent";

/** The place an address belongs to, or null for a page that borrows the place it was opened from. */
export function placeOf(path: string): string | null {
  if (borrows(path)) return null;
  if (path.startsWith("/browse")) return "/browse";
  if (path.startsWith("/discover")) return "/discover";
  if (path.startsWith("/progress")) return "/progress";
  if (path.startsWith("/settings")) return "/settings";
  if (path.startsWith("/staff")) return "/staff";
  return "/";
}

/**
 * The place of the page being shown: its own, or the place of the page it was opened from (none
 * when it was opened directly, so a link from outside highlights nothing and isn't remembered).
 */
const placeByKey = new Map<string, string | null>();
function placeAt(l: Pick<Location, "key" | "pathname">): string | null {
  const known = placeByKey.get(l.key);
  if (known !== undefined) return known;
  // Rendered before tracking catches up, the last page visited is the one this was opened from.
  const place = borrows(l.pathname) ? (visited[visited.length - 1]?.place ?? null) : placeOf(l.pathname);
  placeByKey.set(l.key, place);
  return place;
}

/** The place the current page is in. */
export function usePlace(): string | null {
  return placeAt(useLocation());
}

/** Where a place's link goes: back to where you left it, or to its root when you're already there. */
export function placeLink(place: string, current: string | null): string {
  return current === place ? place : (lastIn.get(place) ?? place);
}

/** Keeps [visited] and the places' memory up to date. Mounted once, inside the router. */
export function useHistoryTracking() {
  const location = useLocation();
  const type = useNavigationType();
  useEffect(() => {
    const entry = { key: location.key, url: at(location), place: placeAt(location) };
    const known = visited.findIndex((v) => v.key === location.key);
    if (type === "POP" && known >= 0) visited.splice(known + 1);
    else if (type === "REPLACE" && visited.length) visited[visited.length - 1] = entry;
    else visited.push(entry);
    if (entry.place) lastIn.set(entry.place, entry.url);
  }, [location, type]);
}

/**
 * The way up from a page anyone can link to: the page it was opened from (passing over signing in
 * and the page itself before it), or [fallback] when it was opened directly.
 */
export function useOpener(fallback: string): string {
  const location = useLocation();
  const url = at(location);
  let i = visited.findIndex((v) => v.key === location.key);
  if (i < 0) i = visited.length;
  while (--i >= 0) {
    const v = visited[i];
    if (v.url !== url && !v.url.startsWith("/sign-in") && !v.url.startsWith("/oauth/")) return v.url;
  }
  return fallback;
}

/**
 * Goes up to [parent]: back, when the page before this one in history is that parent (so browser
 * Back and ← agree), otherwise to the parent in place of this page.
 */
export function useUp() {
  const navigate = useNavigate();
  return (parent: string) => {
    const before = visited[visited.length - 2];
    if (before && (before.url === parent || before.url.split("?")[0] === parent.split("?")[0])) navigate(-1);
    else navigate(parent, { replace: true });
  };
}

/** An overlay kept in the address (`?note=…`): its value, and opening and closing it. */
export function useOverlay(name: string) {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const value = params.get(name);
  const open = (v: string, extra: Record<string, string> = {}) => {
    const next = new URLSearchParams(params);
    next.set(name, v);
    for (const [k, x] of Object.entries(extra)) next.set(k, x);
    setParams(next);
  };
  const close = () => {
    const before = visited[visited.length - 2];
    const next = new URLSearchParams(params);
    next.delete(name);
    if (name === "note") next.delete("in");
    const url = pathname + (next.size ? `?${next}` : "");
    if (before?.url === url) navigate(-1);
    else setParams(next, { replace: true });
  };
  return { value, open, close };
}
