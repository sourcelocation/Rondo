import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { one } from "./dom";
import type { Projection } from "./scenery/projection";
import type { Stage } from "./stage";

/**
 * The flight's film (motion/flight/film.ts), in its section, played by the scroll. The section is the film's timeline,
 * laid out (components/Flight.astro): the flight runs from the section coming into view, the camera setting off from
 * the opening shot as soon as the plane shows, to the end of the track, the stage holding the screen meanwhile, the
 * words above the clouds over it at first, through the window to the laptop, the words of landing coming up over the
 * cabin as the camera comes in. A still end follows, to read by.
 *
 * The film is made on the scenery's GPU and shown by it (motion/scenery/projection.ts), as the page loads, a step at a
 * time on frames when the page is still: the page's opening waits for it (under the intro, or the curtain), so nothing
 * of the making is seen. Until the film has a frame its poster stands in, the opening shot, and gives way to it.
 * Without the scenery, the poster stays.
 */

/**
 * When the words change over, as shares of the flight: those above the clouds leave once the camera is under way from
 * the opening, and those of landing come up once it's through the window, which it is five sixths of the way in
 * (as motion/flight/shots.ts's travel has it).
 */
const WORDS = { leave: 0.556, land: 0.809 } as const;

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
    const words = gsap
      .timeline()
      .to(one(host, "[data-flight-above]"), { autoAlpha: 0, y: -48, ease: "power1.in", duration: 0.067 }, WORDS.leave)
      .fromTo(
        one(host, "[data-flight-landed]"),
        { autoAlpha: 0, y: 32 },
        { autoAlpha: 1, y: 0, ease: "power2.out", duration: 0.089 },
        WORDS.land,
      )
      .set({}, {}, 1);
    ScrollTrigger.create({
      trigger: host,
      start: "top bottom",
      endTrigger: one(host, "[data-flight-track]"),
      end: "bottom bottom",
      animation: words,
      scrub: true,
      onUpdate: ({ progress }) => {
        this.progress = progress;
        this.projection?.play(progress);
      },
    });
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
