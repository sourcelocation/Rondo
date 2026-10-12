import { PUBLIC_RONDO_RELEASE } from "astro:env/client";
import { LAUNCH } from "../launch/launch";

/**
 * /head.js, which every page runs in its head, before it paints. It puts on <html> what the styles need from the
 * first frame: whether Rondo is out yet (data-release, by launch/launch.ts's rules), and the visitor's platform
 * (data-os, data-device), so the right actions show without a flash. It decides whether the page waits for its motion
 * (data-motion), and gives the motion three seconds to take the page over before the page shows without it. And it
 * decides how the page opens: under the curtain the last page ended on when a link of the site's opened it
 * (data-curtain, motion/transition.ts), and with the intro otherwise, quicker once the visit has had it (data-intro,
 * motion/intro.ts). It's a file of its own because the site's CSP allows no inline scripts.
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

  // The motion takes the page over from "pending" (motion/stage.ts); if it hasn't in time, the page shows as it is.
  const still = matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (!still) {
    root.dataset.motion = "pending";
    setTimeout(() => {
      if (root.dataset.motion !== "pending") return;
      root.dataset.motion = "off";
      delete root.dataset.curtain;
      delete root.dataset.intro;
    }, 3000);
  }

  try {
    const curtain = JSON.parse(sessionStorage.getItem("rondo:curtain") || "null");
    sessionStorage.removeItem("rondo:curtain");
    const here = location.pathname.replace(/\\/+$/, "") || "/";
    if (curtain && curtain.to === here && Date.now() - curtain.at < 10000) {
      root.dataset.curtain = "cover";
    } else if (!still) {
      root.dataset.intro = sessionStorage.getItem("rondo:opened") ? "again" : "cover";
      sessionStorage.setItem("rondo:opened", "1");
    }
  } catch {}
})();
`;

export const GET = (): Response =>
  new Response(script, { headers: { "Content-Type": "text/javascript; charset=utf-8" } });
