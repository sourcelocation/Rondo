import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { all, one } from "./dom";
import { CardRing } from "./ring";
import type { Stage } from "./stage";

/**
 * The hero. Its opening, once the curtain has opened the page: the light comes up, the ring's cards are dealt, and
 * the words come in after each other, the bar's logo with them. Scrolling away, the ring opens and fades,
 * and the headline drifts up into a blur; what's under it (the lead and the way in) simply scrolls away, as
 * clickable as anything else until it's gone. The bar's actions, the same as the way in, stay away until the way in
 * has passed under the bar.
 */
export function hero(root: HTMLElement, stage: Stage): void {
  const title = one(root, "[data-hero-title]");
  const ring = new CardRing(one(root, "[data-ring]"), title, () => stage.scroll.velocity, stage.reduced);
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

  ScrollTrigger.create({
    trigger: root,
    start: "top bottom",
    end: "bottom top",
    onToggle: ({ isActive }) => (ring.visible = isActive),
  });
  ScrollTrigger.create({
    trigger: root,
    start: "top top",
    end: "bottom top",
    onUpdate: ({ progress }) => (ring.leaving = progress),
  });
  if (!stage.reduced) {
    gsap.to(title, {
      yPercent: -16,
      scale: 0.94,
      opacity: 0,
      filter: "blur(18px)",
      ease: "power1.in",
      scrollTrigger: { trigger: root, start: "top top", end: "bottom top", scrub: 0.6 },
    });
  }

  stage.open((lightUp) => {
    /** The hero arrives: the light comes up, the cards are dealt, and the words come in after each other. */
    const arrive = () => {
      lightUp();
      if (stage.reduced) return;
      ring.deal();
      const steps: [number, Element | null][] = [
        [0.15, title],
        [0.85, root.querySelector(".hero__lead")],
        [1.15, root.querySelector(".hero__entry")],
        ...all(document, ".nav__side").map((side): [number, Element] => [1.3, side]),
      ];
      for (const [delay, element] of steps) gsap.delayedCall(delay, () => stage.reveals.play(element));
    };
    if (stage.reduced) {
      ring.place();
      arrive();
      stage.reveals.playAll();
      return;
    }
    stage.reveals.play(one(document, ".nav__home"));
    arrive();
  });
}
