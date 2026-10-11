import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { SplitText } from "gsap/SplitText";
import { Aurora } from "./aurora";
import { hasFinePointer, prefersReducedMotion } from "./dom";
import { Pointer } from "./pointer";
import { Reveals } from "./reveal";
import { SmoothScroll } from "./scroll";

gsap.registerPlugin(ScrollTrigger, SplitText);

/** A page's opening: given what brings the light up, for it to call when the page is ready for it. */
export type Opening = (lightUp: () => void) => void;

/**
 * What every scene on a page shares: whether to move at all, the pointer, the scrolling, the light behind the page
 * and the reveals. motion/page.ts makes one for each page; scenes get it when they're mounted (motion/scenes.ts).
 */
export class Stage {
  readonly reduced = prefersReducedMotion();
  /** Whether the visitor points with a mouse or a trackpad, which can hover. */
  readonly fine = hasFinePointer();
  readonly scroll = new SmoothScroll(!this.reduced);
  readonly pointer = new Pointer();
  readonly reveals = new Reveals(document, this.reduced);
  /** The light behind the page, on pages that have it, where WebGL works. */
  readonly aurora: Aurora | null;
  private opening: Opening | null = null;
  private lightUp: (() => void) | null = null;

  constructor() {
    const host = document.querySelector<HTMLElement>("[data-aurora]");
    this.aurora = host == null ? null : Aurora.mount(host, this.reduced, this.pointer);
  }

  /**
   * Gives the page an opening, played once it has arrived on screen (the hero's, on the landing page). It plays the
   * reveals that wait for it, and brings the light up (`lightUp`) when it's ready to. Without one, the light comes up
   * and the reveals play as soon as the page arrives.
   */
  open(opening: Opening): void {
    this.opening = opening;
    if (this.lightUp != null) opening(this.lightUp);
  }

  /** The page is on screen (any transition into it done): its opening plays, and scrolling starts revealing. */
  arrive(lightUp: () => void): void {
    if (this.lightUp != null) return;
    this.lightUp = lightUp;
    this.reveals.watch();
    if (this.opening != null) {
      this.opening(lightUp);
      return;
    }
    lightUp();
    this.reveals.playAll();
  }
}
