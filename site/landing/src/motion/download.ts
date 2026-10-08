import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { SplitText } from "gsap/SplitText";
import { Aurora } from "./aurora";
import { all, hasFinePointer, one, prefersReducedMotion } from "./dom";
import { magnetize } from "./magnetic";
import { Orbit } from "./orbit";
import { Reveals } from "./reveal";
import { SmoothScroll } from "./scroll";
import { spotlight } from "./spotlight";

gsap.registerPlugin(ScrollTrigger, SplitText);

/**
 * The download page's motion: smooth scrolling, the light behind the page, the platforms' orbit settling on the
 * visitor's, cards lit from the pointer, and the reveals.
 */
async function start(): Promise<void> {
  const reduced = prefersReducedMotion();
  new SmoothScroll(!reduced);
  const aurora = Aurora.mount(one(document, "[data-aurora]"), reduced);
  await Promise.race([document.fonts.ready, new Promise((resolve) => setTimeout(resolve, 1500))]);
  aurora?.mood("hero", 2.6);
  new Reveals(document, reduced).start();
  new Orbit(one(document, "[data-orbit]"), document.documentElement.dataset.os ?? "web", reduced);
  if (hasFinePointer()) {
    spotlight(all(document, "[data-spotlight]"));
    if (!reduced) magnetize(all(document, "[data-magnetic]"));
  }
  ScrollTrigger.create({
    start: 24,
    end: "max",
    toggleClass: { targets: one(document, "[data-nav]"), className: "is-scrolled" },
  });
  ScrollTrigger.create({
    trigger: "#platforms",
    start: "top 60%",
    end: "max",
    onToggle: ({ isActive }) => aurora?.mood(isActive ? "maker" : "hero"),
  });
}

void start();
