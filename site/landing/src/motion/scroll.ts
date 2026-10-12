import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";

/**
 * The page's scrolling, which the browser does itself, natively, on every device: on its own thread, at the screen's
 * full rate, however busy the page's script is. Nothing here moves the page; the motion only follows it. ScrollTrigger
 * measures the page again whenever its height changes (fonts arriving, an answer opening, the screen turning), once
 * the change has settled; on its own it measures only as the page opens and as a desktop's window resizes.
 */
export class Scrolling {
  /** The whole page's scroll, followed for how fast it goes. */
  private readonly page = ScrollTrigger.create({ start: 0, end: "max" });
  /** When the page last scrolled (performance.now()). */
  private moved = 0;

  constructor() {
    this.follow();
    window.addEventListener(
      "scroll",
      () => {
        this.moved = performance.now();
      },
      { passive: true },
    );
  }

  /** How fast the page is scrolling, in pixels per frame (of 60 a second). */
  get velocity(): number {
    return this.page.getVelocity() / 60;
  }

  /**
   * Waits for a frame on which the page has been still a moment, for work that holds the main thread up a while: the
   * scroll itself never waits on it, but whatever follows the scroll would.
   */
  async still(): Promise<void> {
    do {
      await new Promise((resolve) => requestAnimationFrame(resolve));
    } while (performance.now() - this.moved < 150);
  }

  /** Has ScrollTrigger measure the page again a moment after its height last changed, so a long change costs one. */
  private follow(): void {
    const remeasure = gsap
      .delayedCall(0.25, () => {
        ScrollTrigger.refresh();
      })
      .pause();
    let height = document.body.offsetHeight;
    new ResizeObserver(() => {
      const now = document.body.offsetHeight;
      if (now === height) return;
      height = now;
      remeasure.restart(true);
    }).observe(document.body);
  }
}
