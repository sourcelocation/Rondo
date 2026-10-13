import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { onResize } from "./dom";
import type { Screen } from "./screen";

/**
 * The page's scrolling, which the browser does itself, natively, on every device: on its own thread, at the screen's
 * full rate, however busy the page's script is. Nothing here moves the page; the motion only follows it.
 *
 * ScrollTrigger measures the page only when this says: once the screen (motion/screen.ts) or the page's height has
 * changed (a phone turned, fonts arriving, an answer opening) and the change has settled, so a long change costs one.
 * Its own measuring on the window's resizes is off: a phone's browser resizes the window as its toolbars come and go,
 * when nothing the motion measures by has changed.
 */
export class Scrolling {
  /** The whole page's scroll, followed for how fast it goes. */
  private readonly page = ScrollTrigger.create({ start: 0, end: "max" });
  /** When the page last scrolled (performance.now()). */
  private moved = 0;

  constructor(screen: Screen) {
    ScrollTrigger.config({ autoRefreshEvents: "visibilitychange,DOMContentLoaded,load" });
    const remeasure = gsap
      .delayedCall(0.25, () => {
        ScrollTrigger.refresh();
      })
      .pause();
    screen.onChange(() => remeasure.restart(true));
    let height = document.body.offsetHeight;
    onResize(document.body, () => {
      const now = document.body.offsetHeight;
      if (now === height) return;
      height = now;
      remeasure.restart(true);
    });
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
}
