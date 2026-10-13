import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { marks, percent, plot, recall, reviews } from "../content/history";
import { all, one, onResize } from "./dom";
import { whileOnScreen } from "./loop";
import { damp, lerp, stepSpring, type Spring } from "./math";
import type { Stage } from "./stage";

/** A jump in the curve this tall is a review, which the point hops across rather than follows. */
const JUMP = 8;
/** How far the curve is drawn as it comes into view: Anki's first days, there from the start. */
const FROM = reviews[2]?.x ?? marks.learned;
/** Where the point waits: just over the seam, so it has crossed. */
const CROSSED = marks.seam + 1;
/**
 * When, through the scroll, the point reaches the seam, and when it moves on: early, while the plot is still coming
 * up the screen, and long enough that the move can't be scrolled past unseen.
 */
const ARRIVE = 0.3;
const LEAVE = 0.56;

/**
 * One card's memory, drawn by the scroll on to now from its first days, which are there as it comes into view. A point
 * travels the curve as the page scrolls, recall falling under it, and hops back to the top at each review on a
 * spring; each review marks the curve as it's reached, and its gap is named. Where the card came over from Anki the
 * point waits a while, and the seam opens up the plot, Anki on one side, greying into the past, and Rondo on the
 * other, lit. Once the point is at now, the year ahead appears, drifting on to the next review. Scrolling back undoes
 * it all. With a mouse, the plot reads out recall wherever it points on what's been drawn.
 */
export class MemoryScene {
  private readonly plot: HTMLElement;
  private readonly drawn: SVGRectElement;
  private readonly ahead: SVGPathElement;
  private readonly root: HTMLElement;
  private readonly reviews: { element: HTMLElement; x: number }[];
  private readonly gaps: { element: HTMLElement; x: number }[];
  private readonly next: HTMLElement;
  private readonly head: HTMLElement;
  private readonly probe: HTMLElement;
  private readonly probeValue: HTMLElement;
  private width = 0;
  private height = 0;
  /** How far the curve is drawn: where the point is. */
  private x: number = marks.now;
  /** How far the point is off the curve, springing back to it after a review's hop. */
  private readonly hop: Spring = { value: 0, velocity: 0 };
  private recalled: number = recall(marks.now);

  constructor(root: HTMLElement, stage: Stage) {
    this.plot = one(root, "[data-memory-plot]");
    this.drawn = one(root, "[data-memory-drawn]", SVGRectElement);
    this.ahead = one(root, "[data-memory-ahead]", SVGPathElement);
    this.root = root;
    this.reviews = all(root, "[data-memory-review]").map((element) => ({
      element,
      x: Number(element.dataset.memoryReview),
    }));
    this.gaps = all(root, "[data-memory-gap]").map((element) => ({ element, x: Number(element.dataset.memoryGap) }));
    this.next = one(root, "[data-memory-next]");
    this.head = one(root, "[data-memory-head]");
    this.probe = one(root, "[data-memory-probe]");
    this.probeValue = one(root, "[data-memory-probe-value]");
    if (stage.fine) this.listen();
    if (stage.reduced) return;

    root.classList.add("is-moving");
    const trigger = ScrollTrigger.create({ trigger: root, start: "top 90%", end: "top 18%" });
    this.x = this.along(trigger.progress);
    this.recalled = recall(this.x);
    onResize(this.plot, () => {
      this.width = this.plot.clientWidth;
      this.height = this.plot.clientHeight;
      this.place();
    });
    whileOnScreen(root, (_time, deltaMs) => this.tick(this.along(trigger.progress), deltaMs));
  }

  /** Where the point is at `progress` through the scroll: drawn on to the seam, held there a while, then on to now. */
  private along(progress: number): number {
    if (progress < ARRIVE) return lerp(FROM, CROSSED, progress / ARRIVE);
    if (progress < LEAVE) return CROSSED;
    return lerp(CROSSED, marks.now, (progress - LEAVE) / (1 - LEAVE));
  }

  private tick(to: number, deltaMs: number): void {
    const seconds = deltaMs / 1000;
    this.x = Math.abs(to - this.x) < 0.05 ? to : damp(this.x, to, 12, seconds);
    const recalled = recall(this.x);
    // Across a review the point stays where it was and springs to where the curve now is.
    if (Math.abs(recalled - this.recalled) > JUMP) this.hop.value += this.recalled - recalled;
    this.recalled = recalled;
    stepSpring(this.hop, 0, 340, 17, seconds);
    this.place();
  }

  /** Draws the curve up to the point, puts the point on it, and marks everything it has reached. */
  private place(): void {
    const { x } = this;
    const y = this.recalled + this.hop.value;
    this.drawn.setAttribute("width", (x - marks.learned + 0.6).toFixed(2));
    this.head.style.translate = `${((x / plot.width) * this.width).toFixed(2)}px ${((y / plot.height) * this.height).toFixed(2)}px`;
    const arrived = x >= marks.now - 0.5;
    for (const review of this.reviews) review.element.classList.toggle("is-on", x >= review.x - 0.5);
    for (const gap of this.gaps) gap.element.classList.toggle("is-on", gap.x > marks.now ? arrived : x >= gap.x - 0.5);
    this.root.classList.toggle("is-moved", x >= marks.seam);
    this.ahead.classList.toggle("is-on", arrived);
    this.next.classList.toggle("is-on", arrived);
  }

  /** Reads out recall under the mouse, on the part of the curve that's been drawn. */
  private listen(): void {
    this.plot.addEventListener("pointermove", (event) => {
      const box = this.plot.getBoundingClientRect();
      const x = ((event.clientX - box.left) / box.width) * plot.width;
      if (x < 0 || x > this.x) {
        this.probe.classList.remove("is-on");
        return;
      }
      const y = recall(x);
      this.probe.style.setProperty("--x", (x / plot.width).toFixed(4));
      this.probe.style.setProperty("--y", (y / plot.height).toFixed(4));
      this.probeValue.textContent = `${Math.round(percent(y))}%`;
      this.probe.classList.add("is-on");
    });
    this.plot.addEventListener("pointerleave", () => this.probe.classList.remove("is-on"));
  }
}

/**
 * Everything that came along from Anki, as one sentence the scroll reads out: each thing named lights up in turn as
 * the sentence passes up the screen, and dims again scrolling back.
 */
export class CarriedScene {
  constructor(root: HTMLElement, still: boolean) {
    if (still) return;
    gsap.fromTo(
      all(root, "[data-carried-item]"),
      { opacity: 0.14 },
      {
        opacity: 1,
        duration: 1,
        stagger: 0.55,
        ease: "none",
        scrollTrigger: { trigger: root, start: "top 82%", end: "bottom 48%", scrub: 0.6 },
      },
    );
  }
}
