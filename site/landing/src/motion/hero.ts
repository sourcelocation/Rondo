import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { all, one } from "./dom";
import type { Stage } from "./stage";

/**
 * The hero. Its opening, once the curtain has opened the page: the light comes up, the camera pulls back out of the
 * ring of cards (the scenery draws them: motion/scenery/ring.ts), and once it's out the words come in after each
 * other, then the bar. Scrolling away, the camera flies back into the ring, and the headline drifts up
 * past it into a blur; what's under it (the lead and the way in) simply scrolls away, as clickable as anything else
 * until it's gone. The bar's actions, the same as the way in, stay away until the way in has passed under the bar.
 */
export function hero(root: HTMLElement, stage: Stage): void {
  const title = one(root, "[data-hero-title]");
  const ring = stage.scenery.then(async (scenery) => {
    if (scenery == null) return null;
    const { CardRing } = await import("./scenery/ring");
    const made = new CardRing(scenery, root, title, stage);
    await made.ready;
    return made;
  });
  stage.prepare(ring);
  const nav = one(document, "[data-nav]");
  const bar = one(nav, ".nav__bar");

  const passed = ScrollTrigger.create({
    trigger: one(root, ".hero__entry"),
    start: () => `bottom ${bar.offsetHeight}px`,
    end: "max",
    onToggle: ({ isActive }) => {
      nav.classList.toggle("is-over-hero", !isActive);
      // The launch list's panel opens from the bar's actions, so it goes with them.
      if (!isActive) for (const panel of all(nav, ":popover-open")) panel.hidePopover();
    },
  });
  nav.classList.toggle("is-over-hero", !passed.isActive);

  if (!stage.reduced) {
    gsap.to(title, {
      yPercent: -16,
      scale: 1.25,
      opacity: 0,
      filter: "blur(18px)",
      ease: "power1.in",
      scrollTrigger: { trigger: root, start: "top top", end: "bottom top", scrub: 0.6 },
    });
  }

  stage.open((lightUp) => {
    // The hero arrives: the light comes up, the camera pulls back out of the ring, and the words come in after each
    // other, then the bar.
    lightUp();
    if (stage.reduced) {
      stage.reveals.playAll();
      return;
    }
    void ring.then((made) => made?.pullBack());
    const steps: [number, Element | null][] = [
      [1.5, title],
      [2.1, root.querySelector(".hero__lead")],
      [2.4, root.querySelector(".hero__entry")],
      [2.55, bar],
    ];
    for (const [delay, element] of steps) gsap.delayedCall(delay, () => stage.reveals.play(element));
  });
}
