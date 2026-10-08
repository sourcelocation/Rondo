import { PUBLIC_RONDO_RELEASE } from "astro:env/client";
import { LAUNCH } from "../launch/launch";

/**
 * /launch.js, which every page runs in its head, before it paints: it puts on <html> whether Rondo is out yet
 * (data-release, by launch/launch.ts's rules) and the visitor's platform (data-os, data-device), so the styles show
 * the right actions without a flash. It's a file of its own because the site's CSP allows no inline scripts.
 */
const script = `(() => {
  const root = document.documentElement;
  const setting = ${JSON.stringify(PUBLIC_RONDO_RELEASE)};
  root.dataset.release = setting === "auto" ? (Date.now() >= ${String(LAUNCH)} ? "live" : "pre") : setting;
  const agent = navigator.userAgent;
  const touchMac = /Macintosh/.test(agent) && navigator.maxTouchPoints > 1;
  const os =
    /iPad/.test(agent) || touchMac ? "ipados"
    : /iPhone|iPod/.test(agent) ? "ios"
    : /Android/.test(agent) ? "android"
    : /CrOS/.test(agent) ? "web"
    : /Windows/.test(agent) ? "windows"
    : /Macintosh/.test(agent) ? "macos"
    : /Linux|X11/.test(agent) ? "linux"
    : "web";
  root.dataset.os = os;
  root.dataset.device = os === "ios" || os === "ipados" || os === "android" ? "mobile" : "desktop";
})();
`;

export const GET = (): Response =>
  new Response(script, { headers: { "Content-Type": "text/javascript; charset=utf-8" } });
