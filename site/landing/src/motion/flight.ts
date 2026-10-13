import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { one } from "./dom";
import type { Tuning } from "./flight/tuning";
import type { Projection } from "./scenery/projection";
import type { Stage } from "./stage";

/**
 * The flight's film (motion/flight/film.ts), in its section, played by the scroll. The section is several screens
 * tall and the film holds the screen through it while the scroll flies the camera in: from the opening shot, which
 * sets off as soon as the section comes into view, the words above the clouds over it, through the window to the
 * laptop, the words of landing coming up over the cabin as the camera comes in. A little of the scroll is left still
 * at the end, to read by.
 *
 * The film is made on the scenery's GPU and shown by it (motion/scenery/projection.ts), as the page loads, a step at a
 * time on frames when the page is still: the page's opening waits for it (under the intro, or the curtain), so nothing
 * of the making is seen. Until the film has a frame its poster stands in, the opening shot, and gives way to it.
 * Without the scenery, the poster stays.
 */

/**
 * The share of the section's scroll, from its coming into view, the flight takes, before a still end; and the share of
 * the flight at which the camera comes through the window (motion/flight/shots.ts).
 */
const FLIGHT = { to: 0.931, window: 0.74 } as const;

export class FlightFilm {
  private projection: Projection | null = null;
  /** How far in the scroll says the camera should be, 0 to 1. */
  private progress = 0;
  /** In development, the panel tuning the flight's timing. */
  private tuning: Tuning | null = null;
  /** The section's scroll, which flies the camera. */
  private readonly scroll: ScrollTrigger | null = null;

  constructor(
    private readonly host: HTMLElement,
    private readonly stage: Stage,
  ) {
    if (stage.reduced) return;
    stage.prepare(this.load());
    // From the section coming into view, so the camera sets off as soon as the plane shows.
    this.scroll = ScrollTrigger.create({
      trigger: host,
      start: "top bottom",
      end: "bottom bottom",
      onUpdate: ({ progress }) => {
        this.progress = Math.min(progress / FLIGHT.to, 1);
        this.projection?.play(this.progress);
        this.tuning?.follow(this.progress);
      },
    });
    // The words: those above the clouds leave once the camera is under way from the opening; those of landing come up
    // once the camera is through the window.
    const through = FLIGHT.window * FLIGHT.to;
    gsap
      .timeline({ scrollTrigger: { trigger: host, start: "top bottom", end: "bottom bottom", scrub: true } })
      .to(one(host, "[data-flight-above]"), { autoAlpha: 0, y: -48, ease: "power1.in", duration: 0.062 }, 0.517)
      .fromTo(
        one(host, "[data-flight-landed]"),
        { autoAlpha: 0, y: 32 },
        { autoAlpha: 1, y: 0, ease: "power2.out", duration: 0.083 },
        through + 0.014,
      )
      .set({}, {}, 1);
    // In development, with ?tune in the address, a panel for tuning the flight's timing.
    if (import.meta.env.DEV && new URLSearchParams(location.search).has("tune"))
      void import("./flight/tuning").then(({ Tuning }) => (this.tuning = new Tuning(this)));
  }

  /** How far in the camera is, 0 to 1. */
  get flown(): number {
    return this.progress;
  }

  /** Scrolls the page to `progress` of the flight. */
  seek(progress: number): void {
    const { scroll } = this;
    if (scroll == null) return;
    window.scrollTo({ top: scroll.start + (scroll.end - scroll.start) * progress * FLIGHT.to, behavior: "instant" });
  }

  /** Draws the film again where it is, as after its timing changes. */
  redraw(): void {
    this.projection?.jump(this.progress);
  }

  private async load(): Promise<void> {
    try {
      const scenery = await this.stage.scenery;
      if (scenery == null) return;
      const { Projection } = await import("./scenery/projection");
      // Small screens and slower devices get a lighter film: fewer steps through the clouds, and fewer pixels.
      const light = this.host.clientWidth < 760 || navigator.hardwareConcurrency <= 4;
      const projection = await Projection.create(scenery, this.host, { light }, this.stage.pointer, () =>
        this.stage.scroll.still(),
      );
      projection.jump(this.progress);
      scenery.warm(projection);
      this.projection = projection;
    } catch (error) {
      console.error(error);
    }
  }
}
