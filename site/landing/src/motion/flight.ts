import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { one } from "./dom";
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
const FLIGHT = { to: 0.9, window: 0.61 } as const;

export class FlightFilm {
  private projection: Projection | null = null;
  /** How far in the scroll says the camera should be, 0 to 1. */
  private progress = 0;

  constructor(
    private readonly host: HTMLElement,
    private readonly stage: Stage,
  ) {
    if (stage.reduced) return;
    stage.prepare(this.load());
    // From the section coming into view, so the camera sets off as soon as the plane shows.
    ScrollTrigger.create({
      trigger: host,
      start: "top bottom",
      end: "bottom bottom",
      onUpdate: ({ progress }) => {
        this.progress = Math.min(progress / FLIGHT.to, 1);
        this.projection?.play(this.progress);
      },
    });
    // The words: those above the clouds leave once the film holds the screen; those of landing come up once the camera
    // is through the window.
    const through = FLIGHT.window * FLIGHT.to;
    gsap
      .timeline({ scrollTrigger: { trigger: host, start: "top bottom", end: "bottom bottom", scrub: true } })
      .to(one(host, "[data-flight-above]"), { autoAlpha: 0, y: -48, ease: "power1.in", duration: 0.09 }, 0.3)
      .fromTo(
        one(host, "[data-flight-landed]"),
        { autoAlpha: 0, y: 32 },
        { autoAlpha: 1, y: 0, ease: "power2.out", duration: 0.12 },
        through + 0.02,
      )
      .set({}, {}, 1);
  }

  private async load(): Promise<void> {
    try {
      const scenery = await this.stage.scenery;
      if (scenery == null) return;
      const { Projection } = await import("./scenery/projection");
      // Small screens and slower devices get a lighter film: fewer steps through the clouds, and fewer pixels.
      const light = this.host.clientWidth < 760 || navigator.hardwareConcurrency <= 4;
      const projection = await Projection.create(scenery, this.host, { light }, () => this.stage.scroll.still());
      projection.jump(this.progress);
      scenery.warm(projection);
      this.projection = projection;
    } catch (error) {
      console.error(error);
    }
  }
}
