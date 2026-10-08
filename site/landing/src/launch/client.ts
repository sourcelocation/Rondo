import { gsap } from "gsap";
import { all, prefersReducedMotion } from "../motion/dom";
import { Countdown } from "./countdown";
import { followsClock, LAUNCH, launchDay, launchMoment } from "./launch";
import { NotifyForm, NotifyPanel } from "./notify";

/** Told to the page when Rondo comes out while it's open. */
export const LAUNCHED = "rondo:launched";

/**
 * The launch, on every page: the launch list's forms and panels, launch dates in the visitor's own time zone, any
 * countdown, and, if the page is open when the moment comes, the turn from the launch list to the apps.
 */
function start(): void {
  for (const form of all(document, "[data-notify]", HTMLFormElement)) new NotifyForm(form);
  for (const panel of all(document, "[data-notify-panel]")) new NotifyPanel(panel);
  for (const time of all(document, "[data-launch-day]")) time.textContent = launchDay();
  for (const time of all(document, "[data-launch-moment]")) time.textContent = launchMoment();
  for (const element of all(document, "[data-countdown]")) new Countdown(element);

  const root = document.documentElement;
  const wait = LAUNCH - Date.now();
  // setTimeout can't wait longer than about 24 days; a page open that long can be reloaded.
  if (followsClock && root.dataset.release === "pre" && wait > 0 && wait < 2 ** 31 - 1) {
    setTimeout(() => {
      root.dataset.release = "live";
      document.dispatchEvent(new Event(LAUNCHED));
      arrive();
    }, wait);
  }
}

/** What the launch brings onto the page arrives out of a blur, rather than simply appearing. */
function arrive(): void {
  if (prefersReducedMotion()) return;
  const shown = all(document, '[data-when="live"]').filter((element) => element.getClientRects().length > 0);
  gsap.fromTo(
    shown,
    { opacity: 0, y: 10, filter: "blur(10px)" },
    {
      opacity: 1,
      y: 0,
      filter: "blur(0px)",
      duration: 1.2,
      stagger: 0.06,
      ease: "expo.out",
      clearProps: "opacity,transform,filter",
    },
  );
}

start();
