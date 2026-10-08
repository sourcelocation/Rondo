import { gsap } from "gsap";
import { one } from "./dom";

/**
 * The opening. The mark appears in a sweep that turns counter-clockwise, as its arrow does, then rises into the
 * logo in the bar and becomes its mark there while the wordmark breathes in beside it.
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

  /** The sweep and the flight; `landed` runs as the mark settles, for the page to follow. */
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
      this.fly(timeline.time(), timeline);
      landed();
    });
    return timeline;
  }

  /** Flies the mark from the centre onto the logo's, measured as it leaves. */
  private fly(at: number, timeline: gsap.core.Timeline): void {
    const from = one(this.overlay, "svg", SVGElement).getBoundingClientRect();
    const to = this.mark.getBoundingClientRect();
    const dx = to.left + to.width / 2 - (from.left + from.width / 2);
    const dy = to.top + to.height / 2 - (from.top + from.height / 2);
    const duration = 1.15;
    timeline.to(this.overlay, { x: dx, y: dy, scale: to.height / from.height, duration, ease: "expo.inOut" }, at);
    timeline.set(this.mark, { opacity: 1 }, at + duration);
    timeline.set(this.overlay, { visibility: "hidden" }, at + duration);
    timeline.fromTo(
      this.word,
      { opacity: 0, x: -14, filter: "blur(10px)" },
      { opacity: 1, x: 0, filter: "blur(0px)", duration: 1.3, ease: "expo.out", clearProps: "filter,transform" },
      at + duration - 0.25,
    );
  }
}
