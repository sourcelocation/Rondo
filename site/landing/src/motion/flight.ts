import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { one } from "./dom";
import type { Film } from "./flight/film";
import type { Stage } from "./stage";

/**
 * The flight's film (motion/flight/film.ts), in its section, played by the scroll. The section is several screens
 * tall and the film holds the screen through it while the scroll flies the camera in: from the opening shot, the
 * words above the clouds over it, through the window to the laptop, where the words of landing come up over it. A
 * little of the scroll is left still at either end, to read by.
 *
 * three.js and the film's models are fetched and made only as the section comes within a screen or so, so the page
 * never waits for them; until the film has drawn its first frame its poster stands in, the opening shot, and the film
 * fades in over it. With reduced motion, or without WebGL 2, the poster stays.
 */

/** The share of the section's scroll the flight takes, after a still start and before a still end. */
const FLIGHT = { from: 0.06, to: 0.86 } as const;

export class FlightFilm {
  private film: Film | null = null;
  private showing = false;
  /** How far in the scroll says the camera should be, 0 to 1. */
  private progress = 0;
  /** Where the film draws, over the poster. */
  private readonly screen: HTMLElement;

  constructor(
    private readonly host: HTMLElement,
    stage: Stage,
  ) {
    this.screen = one(host, "[data-flight-screen]");
    if (stage.reduced) return;
    ScrollTrigger.create({
      trigger: host,
      start: "top bottom+=110%",
      once: true,
      onEnter: () => void this.load(),
    });
    ScrollTrigger.create({
      trigger: host,
      start: "top bottom",
      end: "bottom top",
      onToggle: ({ isActive }) => {
        this.showing = isActive;
        this.update();
      },
    });
    ScrollTrigger.create({
      trigger: host,
      start: "top top",
      end: "bottom bottom",
      onUpdate: ({ progress }) => {
        this.progress = Math.min(Math.max((progress - FLIGHT.from) / (FLIGHT.to - FLIGHT.from), 0), 1);
        this.film?.play(this.progress);
      },
    });
    // The words: those above the clouds leave as the camera sets off; those of landing come as it settles.
    gsap
      .timeline({ scrollTrigger: { trigger: host, start: "top top", end: "bottom bottom", scrub: true } })
      .to(one(host, "[data-flight-above]"), { autoAlpha: 0, y: -48, ease: "power1.in", duration: 0.12 }, 0.03)
      .fromTo(
        one(host, "[data-flight-landed]"),
        { autoAlpha: 0, y: 32 },
        { autoAlpha: 1, y: 0, ease: "power2.out", duration: 0.1 },
        0.84,
      )
      .set({}, {}, 1);
  }

  private async load(): Promise<void> {
    try {
      const { Film } = await import("./flight/film");
      // Small screens get a lighter film: fewer steps through the clouds, and fewer pixels.
      const light = this.host.clientWidth < 760 || navigator.hardwareConcurrency <= 4;
      const film = await Film.create(this.screen, { light });
      if (film == null) return;
      film.onShown = () => this.host.classList.add("is-playing");
      film.jump(this.progress);
      this.film = film;
      this.update();
    } catch (error) {
      console.error(error);
    }
  }

  private update(): void {
    const { film } = this;
    if (film == null) return;
    if (this.showing) film.show();
    else film.hide();
  }
}
