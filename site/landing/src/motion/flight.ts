import { PUBLIC_RONDO_TWEAK } from "astro:env/client";
import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { all, one } from "./dom";
import type { Projection } from "./scenery/projection";
import type { Stage } from "./stage";

/**
 * The flight's film (motion/flight/film.ts), in its section, played by the scroll. The section is the film's timeline,
 * laid out (components/Flight.astro): the flight runs from the section coming into view, the camera setting off from
 * the opening shot as soon as the plane shows, to the end of the track, the stage holding the screen meanwhile, the
 * words above the clouds over it at first, through the window to the laptop, the words of landing over the cabin as
 * the camera comes in. The words are held on the screen (components/Flight.astro), and come and go in their own time:
 * the scroll only says when.
 *
 * The film is made on the scenery's GPU and shown by it (motion/scenery/projection.ts), as the page loads, a step at a
 * time on frames when the page is still: the page's opening waits for it (under the intro, or the curtain), so nothing
 * of the making is seen. Until the film has a frame its poster stands in, the opening shot, and gives way to it.
 * Without the scenery, the poster stays.
 */

/**
 * When the words come and go, as shares of the flight (motion/flight/tuning.ts tunes it, with --tweak): those above
 * the clouds come as the section's top nears the screen's, and change over to those of landing just before the camera's
 * through the window, which it is about seven eighths of the way in (as motion/flight/shots.ts's move has it), which
 * go a little after the flight, as the section goes on.
 */
export const words = { arrive: 0.15, change: 0.86, go: 1.08 };

export class FlightFilm {
  private projection: Projection | null = null;
  /** How far in the scroll says the camera should be, 0 to 1. */
  private scrolled = 0;
  /** The flight, from the section coming into view to the end of the track. */
  private flight: ScrollTrigger | null = null;
  /** The words, each shown while the scroll's within its stretch of the flight. */
  private readonly cues: Cue[] = [];
  /** Whether the words are to be changed over once the scroll's triggers have all had their say. */
  private syncing = false;

  constructor(
    private readonly host: HTMLElement,
    private readonly stage: Stage,
  ) {
    if (stage.reduced) return;
    stage.prepare(this.load());
    host.classList.add("is-live");
    this.flight = ScrollTrigger.create({
      trigger: host,
      start: "top bottom",
      endTrigger: one(host, "[data-flight-track]"),
      end: "bottom bottom",
      onUpdate: ({ progress }) => {
        this.scrolled = progress;
        this.projection?.play(progress);
      },
    });
    this.cue(
      gsap.fromTo(
        one(host, "[data-flight-above]"),
        { autoAlpha: 0, y: 32 },
        { autoAlpha: 1, y: 0, ease: "power3.out", duration: 0.9, paused: true },
      ),
      () => words.arrive,
      () => words.change,
    );
    this.cue(
      gsap.fromTo(
        all(one(host, "[data-flight-landed]"), ":scope > *"),
        { autoAlpha: 0, y: 24 },
        { autoAlpha: 1, y: 0, ease: "power3.out", duration: 0.9, stagger: 0.1, paused: true },
      ),
      () => words.change,
      () => words.go,
    );
    // With --tweak, a panel for tuning the flight.
    if (PUBLIC_RONDO_TWEAK) void import("./flight/tuning").then(({ Tuning }) => new Tuning(host, this));
  }

  /** How far in the scroll says the camera should be, 0 to 1. */
  get progress(): number {
    return this.scrolled;
  }

  /** Shows the flight again where the scroll is, as it's tuned: the words where `words` says, and the film. */
  retune(): void {
    for (const { trigger } of this.cues) trigger.refresh();
    this.sync();
    this.projection?.jump(this.scrolled);
  }

  /**
   * Shows `words` once the scroll's past `from` of the flight, until it's past `to`, or back before `from`: in their own
   * time, whatever the scroll's pace.
   */
  private cue(words: gsap.core.Tween, from: () => number, to: () => number): void {
    const at = (share: number) => {
      const { start, end } = this.flight ?? { start: 0, end: 0 };
      return start + (end - start) * share;
    };
    const trigger = ScrollTrigger.create({
      trigger: this.host,
      start: () => at(from()),
      end: () => at(to()),
      // After the flight's own, which these are measured along.
      refreshPriority: -1,
      onToggle: () => this.resync(),
    });
    this.cues.push(new Cue(trigger, words));
  }

  /**
   * Changes the words over once every trigger the scroll passed has toggled: where one stretch ends and the next
   * begins, the two toggle together, in either order.
   */
  private resync(): void {
    if (this.syncing) return;
    this.syncing = true;
    queueMicrotask(() => {
      this.syncing = false;
      this.sync();
    });
  }

  /** Shows the words whose stretch the scroll's within: those going leave first, those coming wait till they're gone. */
  private sync(): void {
    for (const cue of this.cues) if (!cue.trigger.isActive) cue.leave();
    const clear = Math.max(0, ...this.cues.map((cue) => cue.leaving));
    for (const cue of this.cues) if (cue.trigger.isActive) cue.enter(clear);
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
      projection.jump(this.scrolled);
      scenery.warm(projection);
      this.projection = projection;
    } catch (error) {
      console.error(error);
    }
  }
}

/** Words shown while the scroll's within their stretch of the flight: rising in slowly, and out quickly, upwards. */
class Cue {
  private shown = false;
  private exit: gsap.core.Tween | null = null;

  constructor(
    readonly trigger: ScrollTrigger,
    /** Their way in, from hidden. */
    private readonly words: gsap.core.Tween,
  ) {}

  /** How long till they're gone, in seconds, if they're on their way out. */
  get leaving(): number {
    return this.exit == null ? 0 : this.exit.duration() * (1 - this.exit.progress());
  }

  /** Comes in, after `wait` seconds, unless already shown. */
  enter(wait: number): void {
    if (this.shown) return;
    this.shown = true;
    this.exit?.kill();
    this.exit = null;
    this.words.delay(wait).restart(true);
  }

  /** Goes, unless already gone. */
  leave(): void {
    if (!this.shown) return;
    this.shown = false;
    this.words.pause();
    this.exit = gsap.to(this.words.targets(), { autoAlpha: 0, y: -24, ease: "power2.in", duration: 0.35 });
  }
}
