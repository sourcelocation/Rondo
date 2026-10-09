import { useEffect } from "react";
import { useLocation, useNavigate, useNavigationType, useSearchParams, type Location } from "react-router";

/**
 * How the app's addresses relate: every page has one level above it, overlays sit on the page that
 * opened them (in its search params), and each place in the sidebar, tab bar or account menu
 * remembers where it was left. Up (← and Esc) goes one level up, retracing history when that's
 * where you came from.
 */

const at = (l: Pick<Location, "pathname" | "search">) => l.pathname + l.search;

/** This tab's history inside the app, as the router moved through it. */
const visited: { key: string; url: string }[] = [];

/** The places: roots of the sidebar, the tab bar and the account menu. Each remembers where it was left. */
export const places = ["/", "/browse", "/discover", "/progress", "/settings", "/staff"] as const;
const lastIn = new Map<string, string>();

/** The place an address belongs to. */
export function placeOf(path: string): string {
  if (path.startsWith("/browse")) return "/browse";
  if (path.startsWith("/discover") || path.startsWith("/d/") || path.startsWith("/u/")) return "/discover";
  if (path.startsWith("/progress")) return "/progress";
  if (path.startsWith("/settings")) return "/settings";
  if (path.startsWith("/staff")) return "/staff";
  return "/";
}

/** Where a place's link goes: back to where you left it, or to its root when you're already there. */
export function placeLink(place: string, current: string): string {
  return placeOf(current) === place ? place : (lastIn.get(place) ?? place);
}

/** Keeps [visited] and the places' memory up to date. Mounted once, inside the router. */
export function useHistoryTracking() {
  const location = useLocation();
  const type = useNavigationType();
  useEffect(() => {
    const entry = { key: location.key, url: at(location) };
    const known = visited.findIndex((v) => v.key === location.key);
    if (type === "POP" && known >= 0) visited.splice(known + 1);
    else if (type === "REPLACE" && visited.length) visited[visited.length - 1] = entry;
    else visited.push(entry);
    lastIn.set(placeOf(location.pathname), entry.url);
  }, [location, type]);
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
