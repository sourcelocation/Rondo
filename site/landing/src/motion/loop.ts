import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";

/** Runs `tick` on every frame while `element` is on screen, and not at all while it isn't. */
export function whileOnScreen(element: Element, tick: gsap.TickerCallback): void {
  ScrollTrigger.create({
    trigger: element,
    start: "top bottom",
    end: "bottom top",
    onToggle: ({ isActive }) => {
      if (isActive) gsap.ticker.add(tick);
      else gsap.ticker.remove(tick);
    },
  });
}

/** Where a story counts as in view, as ScrollTrigger's start and end. */
interface View {
  readonly start: string;
  readonly end: string;
}

/**
 * Tells a story on a loop while `root` is in view: `tell` puts it together from its first frame, and it plays,
 * pauses while the root is out of view, and is put together afresh each time it ends.
 */
export function loopInView(
  root: Element,
  tell: () => gsap.core.Timeline,
  view: View = { start: "top 72%", end: "bottom 12%" },
): void {
  let timeline: gsap.core.Timeline | null = null;
  let showing = false;
  const play = (): void => {
    if (timeline != null) {
      timeline.play();
      return;
    }
    timeline = tell().eventCallback("onComplete", () => {
      timeline = null;
      if (showing) play();
    });
  };
  ScrollTrigger.create({
    trigger: root,
    start: view.start,
    end: view.end,
    onToggle: ({ isActive }) => {
      showing = isActive;
      if (isActive) play();
      else timeline?.pause();
    },
  });
}
