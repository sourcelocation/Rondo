import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import Lenis from "lenis";

/**
 * The page's scrolling. Lenis eases the wheel into a glide on GSAP's clock and tells ScrollTrigger where the page
 * is; touch scrolling stays the device's own. With reduced motion the page scrolls natively.
 */
export class SmoothScroll {
  private readonly lenis: Lenis | null;

  constructor(smooth: boolean) {
    if (!smooth) {
      this.lenis = null;
      return;
    }
    const lenis = new Lenis({ lerp: 0.085, wheelMultiplier: 0.95, autoRaf: false, anchors: true });
    lenis.on("scroll", () => {
      ScrollTrigger.update();
    });
    gsap.ticker.add((time) => {
      lenis.raf(time * 1000);
    });
    gsap.ticker.lagSmoothing(0);
    this.lenis = lenis;
  }

  /** How fast the page is scrolling, in pixels per frame; 0 when it scrolls natively. */
  get velocity(): number {
    return this.lenis?.velocity ?? 0;
  }
}
