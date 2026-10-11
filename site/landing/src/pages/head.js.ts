import { PUBLIC_RONDO_RELEASE } from "astro:env/client";
import { LAUNCH } from "../launch/launch";

/**
 * /head.js, which every page runs in its head, before it paints. It puts on <html> what the styles need from the
 * first frame: whether Rondo is out yet (data-release, by launch/launch.ts's rules), the visitor's platform (data-os,
 * data-device), so the right actions show without a flash, and whether the page opens under the curtain (data-curtain,
 * motion/transition.ts): the one the last page ended on, the one still coming over the last page's picture
 * ("passing", told to the motion by window.rondoCurtain), or, for a visit's first page, one of its own. It's a file
 * of its own because the site's CSP allows no inline scripts.
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
  try {
    const curtain = JSON.parse(sessionStorage.getItem("rondo:curtain") || "null");
    sessionStorage.removeItem("rondo:curtain");
    const here = location.pathname.replace(/\\/+$/, "") || "/";
    if (curtain && curtain.to === here && Date.now() - curtain.at < 10000) {
      root.dataset.curtain = "cover";
      if (curtain.passing) {
        const passed = typeof curtain.passed === "number" ? curtain.passed : Date.now() - curtain.at;
        addEventListener("pagereveal", (event) => {
          if (!event.viewTransition) return;
          root.dataset.curtain = "passing";
          window.rondoCurtain = { transition: event.viewTransition, passed, shown: performance.now() };
        }, { once: true });
      }
    } else if (!sessionStorage.getItem("rondo:opened") && !matchMedia("(prefers-reduced-motion: reduce)").matches) {
      sessionStorage.setItem("rondo:opened", "1");
      root.dataset.curtain = "open";
    }
  } catch {}
})();
`;

export const GET = (): Response =>
  new Response(script, { headers: { "Content-Type": "text/javascript; charset=utf-8" } });
