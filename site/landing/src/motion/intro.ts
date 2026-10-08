import { gsap } from "gsap";
import { one } from "./dom";

/**
 * The opening. The mark appears in a sweep that turns counter-clockwise, as its arrow does, then rises and fades
 * away while the logo in the bar fades in.
 */
export class Intro {
  private readonly mark: SVGElement;
  private readonly word: SVGElement;

  constructor(
    private readonly overlay: HTMLElement,
    private readonly logo: HTMLElement,
  ) {
    this.mark = one(logo, "[data-logo-mark]", SVGElement);
    this.word = one(logo, "[data-logo-word]", SVGElement);
  }

  /** Whether the intro should play: once a session, from the top, when the page was `quick` to start. */
  static wanted(quick: boolean): boolean {
    try {
      if (sessionStorage.getItem("rondo:intro") != null) return false;
      sessionStorage.setItem("rondo:intro", "1");
    } catch {
      // Storage may be unavailable; the intro then plays every time.
    }
    return quick && window.scrollY < 8;
  }

  /** The sweep and the hand-off to the bar; `landed` runs as the mark leaves, for the page to follow. */
  play(landed: () => void): gsap.core.Timeline {
    gsap.set(this.logo, { opacity: 1, clearProps: "filter" });
    gsap.set([this.mark, this.word], { opacity: 0 });
    gsap.set(this.overlay, { visibility: "visible" });

    const timeline = gsap.timeline();
    timeline.fromTo(
      this.overlay,
      { "--sweep": "0deg", rotate: 24, scale: 0.86, filter: "blur(10px)" },
      { "--sweep": "384deg", rotate: 0, scale: 1, filter: "blur(0px)", duration: 1.35, ease: "power3.inOut" },
    );
    timeline.to(this.overlay, { scale: 1.04, duration: 0.35, ease: "sine.inOut", yoyo: true, repeat: 1 }, "-=0.05");
    timeline.add(() => {
      this.leave(timeline.time(), timeline);
      landed();
    });
    return timeline;
  }

  /** The mark rises into a blur as the logo in the bar comes in out of one. */
  private leave(at: number, timeline: gsap.core.Timeline): void {
    timeline.to(this.overlay, { y: -56, opacity: 0, filter: "blur(12px)", duration: 0.9, ease: "power2.in" }, at);
    timeline.set(this.overlay, { visibility: "hidden" }, at + 0.9);
    timeline.fromTo(
      [this.mark, this.word],
      { opacity: 0, filter: "blur(8px)" },
      { opacity: 1, filter: "blur(0px)", duration: 1.1, ease: "power2.out", clearProps: "filter" },
      at,
    );
  }
}
