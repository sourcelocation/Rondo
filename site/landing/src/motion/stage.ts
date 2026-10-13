import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { SplitText } from "gsap/SplitText";
import { hasFinePointer, one } from "./dom";
import { Edges } from "./edges";
import { Pointer } from "./pointer";
import { Reveals } from "./reveal";
import type { Scenery } from "./scenery";
import { Screen } from "./screen";
import { Scrolling } from "./scroll";

gsap.registerPlugin(ScrollTrigger, SplitText);

/** A page's opening: given what brings the light up, for it to call when the page is ready for it. */
export type Opening = (lightUp: () => void) => void;

/**
 * What every scene on a page shares: whether to move at all, the screen and the page past its edges, the pointer, the
 * scrolling, the scenery behind the page and the reveals. motion/page.ts makes one for each page; scenes get it when they're mounted
 * (motion/scenes.ts).
 */
export class Stage {
  /**
   * Whether the page keeps still, everything shown in place: when the visitor asked for less motion, and when the
   * page showed itself before its motion came (/head.js's data-motion "off"), as nothing shown should hide again.
   */
  readonly reduced: boolean;
  /** Whether the visitor points with a mouse or a trackpad, which can hover. */
  readonly fine = hasFinePointer();
  readonly screen = new Screen(one(document, "[data-screen]"));
  readonly edges = new Edges(document.documentElement);
  readonly scroll = new Scrolling(this.screen);
  readonly pointer = new Pointer(this.screen);
  readonly reveals: Reveals;
  /**
   * The scenery behind the page (motion/scenery.ts), on pages that have it: fetched with three.js as the page opens,
   * so nothing waits for it, and made once the page is calm (under the intro, once its mark has swept in). Null where
   * the page keeps still, and where WebGL isn't there.
   */
  readonly scenery: Promise<Scenery | null>;
  /** Resolves once the scenery's code has been fetched and run (or there's none to fetch). */
  readonly loaded: Promise<unknown>;
  private readonly preparing: Promise<unknown>[] = [];
  private opening: Opening | null = null;
  private lightUp: (() => void) | null = null;
  private arrival: () => void = () => undefined;
  /** Resolves once the page is on screen, any transition into it done. */
  readonly arrived = new Promise<void>((resolve) => {
    this.arrival = resolve;
  });

  /** `calm` resolves once the page can take heavy work (the scenery made); null if it can at once. */
  constructor(calm: Promise<void> | null = null) {
    const root = document.documentElement;
    // The motion takes the page over from /head.js, unless the page has already shown itself without it.
    if (root.dataset.motion === "pending") root.dataset.motion = "on";
    this.reduced = root.dataset.motion !== "on";
    this.reveals = new Reveals(document, this.reduced);
    const host = document.querySelector<HTMLElement>("[data-scenery]");
    const code = host == null || this.reduced ? null : import("./scenery");
    this.loaded = code?.catch(() => null) ?? Promise.resolve();
    this.scenery =
      code == null || host == null
        ? Promise.resolve(null)
        : Promise.all([code, calm])
            .then(([{ Scenery }]) => Scenery.mount(host, this))
            .catch((error: unknown) => {
              console.error(error);
              return null;
            });
  }

  /**
   * Has the page's opening wait for `work`: the scenery's pictures being made ready, so the work doesn't hold up the
   * opening's motion, or anything else moving.
   */
  prepare(work: Promise<unknown>): void {
    this.preparing.push(work);
  }

  /** Resolves once the scenery and everything the opening waits for are ready, or `patience` ms have passed. */
  async prepared(patience: number): Promise<void> {
    await Promise.race([
      Promise.allSettled([this.scenery, ...this.preparing]),
      new Promise((resolve) => setTimeout(resolve, patience)),
    ]);
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
    this.arrival();
    this.reveals.watch();
    if (this.opening != null) {
      this.opening(lightUp);
      return;
    }
    lightUp();
    this.reveals.playAll();
  }
}
