import { gsap } from "gsap";
import { all } from "./dom";
import type { Stage } from "./stage";

/**
 * Depth, as the page scrolls. An element marked data-depth drifts against the scroll as it passes through the
 * screen, by that share of the screen's height (above 0 it lags, as if further away; below, it runs ahead, nearer),
 * so the page moves in layers. An element marked data-tilt rises out of a tilt, its far edge tipped away, as it
 * scrolls into view. Both follow the scroll exactly, and neither moves with reduced motion. Neither goes on an
 * element revealed whole (data-reveal fade or rise), whose transform the reveal clears.
 */
export function depth(stage: Stage): void {
  if (stage.reduced) return;
  for (const element of all(document, "[data-depth]")) {
    const share = Number(element.dataset.depth) || 0;
    // Lagging is sitting lower than its place as it comes in and higher as it goes: it travels less than the page.
    gsap.fromTo(
      element,
      { y: () => -share * window.innerHeight },
      {
        y: () => share * window.innerHeight,
        ease: "none",
        scrollTrigger: {
          trigger: element,
          start: "top bottom",
          end: "bottom top",
          scrub: true,
          invalidateOnRefresh: true,
        },
      },
    );
  }
  for (const element of all(document, "[data-tilt]")) {
    gsap.fromTo(
      element,
      { rotationX: 16, y: 60, scale: 0.96, opacity: 0.35, transformPerspective: 1600, transformOrigin: "50% 100%" },
      {
        rotationX: 0,
        y: 0,
        scale: 1,
        opacity: 1,
        ease: "power1.out",
        scrollTrigger: { trigger: element, start: "top bottom", end: "top 40%", scrub: true },
      },
    );
  }
}
