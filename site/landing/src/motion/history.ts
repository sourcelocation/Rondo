import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { chart, fall, marks, recall, reviews, type Review } from "../content/history";
import { all, one } from "./dom";

/** How fast time passes between reviews, in chart units a second. */
const SPEED = 112;
/** How long a review takes to bring recall back to the top. */
const LIFT = 0.36;

const numbers = new Intl.NumberFormat("en");

/**
 * One card's memory moving from Anki to Rondo, told on a loop while it's on screen. A point travels the card's recall
 * through time: it falls between reviews and springs back at each, the gaps growing. Partway through, the Anki
 * package drops onto the curve and the deck is imported there; the point carries on in Rondo without a break, and
 * the schedule ahead fades in. The markup is the story's last frame, so it reads without the motion.
 */
export class HistoryScene {
  private readonly svg: SVGSVGElement;
  private readonly drawn: SVGRectElement;
  private readonly ahead: SVGRectElement;
  private readonly seam: SVGLineElement;
  private readonly seamLabel: SVGTextElement;
  private readonly dots: SVGCircleElement[];
  private readonly next: SVGCircleElement;
  private readonly gaps: SVGTextElement[];
  private readonly regions: SVGGElement[];
  private readonly playhead: SVGLineElement;
  private readonly glow: SVGCircleElement;
  private readonly ripple: SVGCircleElement;
  private readonly head: SVGCircleElement;
  private readonly pack: HTMLElement;
  private readonly counts: HTMLElement[];
  private readonly stats: HTMLElement;
  private readonly point: { x: number; y: number } = { x: marks.learned, y: chart.top };
  /** Where the point will have got to, as the story is put together. */
  private planned: number = marks.learned;
  private timeline: gsap.core.Timeline | null = null;
  private showing = false;

  constructor(root: HTMLElement, still: boolean) {
    this.svg = one(root, "[data-history-svg]", SVGSVGElement);
    this.drawn = one(root, "[data-history-drawn]", SVGRectElement);
    this.ahead = one(root, "[data-history-ahead]", SVGRectElement);
    this.seam = one(root, "[data-history-seam]", SVGLineElement);
    this.seamLabel = one(root, "[data-history-seam-label]", SVGTextElement);
    this.dots = all(root, "[data-history-dot]", SVGCircleElement);
    this.next = one(root, "[data-history-next]", SVGCircleElement);
    this.gaps = all(root, "[data-history-gap]", SVGTextElement);
    this.regions = all(root, "[data-history-region]", SVGGElement);
    this.playhead = one(root, "[data-history-playhead]", SVGLineElement);
    this.glow = one(root, "[data-history-glow]", SVGCircleElement);
    this.ripple = one(root, "[data-history-ripple]", SVGCircleElement);
    this.head = one(root, "[data-history-head]", SVGCircleElement);
    this.pack = one(root, "[data-history-package]");
    this.counts = all(root, "[data-count]");
    this.stats = one(root, "[data-history-stats]");
    if (still) return;

    this.clear();
    ScrollTrigger.create({
      trigger: root,
      start: "top 72%",
      end: "bottom 12%",
      onToggle: ({ isActive }) => {
        this.showing = isActive;
        if (isActive) this.play();
        else this.timeline?.pause();
      },
    });
  }

  /** Plays the story, again and again while it's on screen. */
  private play(): void {
    if (this.timeline != null) {
      this.timeline.play();
      return;
    }
    this.timeline = this.story().eventCallback("onComplete", () => {
      this.timeline = null;
      if (this.showing) this.play();
    });
  }

  /** Everything back to before the card was learned. */
  private clear(): void {
    const impact = recall(marks.seam);
    gsap.set(this.drawn, { attr: { width: marks.learned } });
    gsap.set(this.ahead, { attr: { width: 0 } });
    gsap.set(this.seam, { attr: { y1: impact, y2: impact } });
    gsap.set([...this.dots, this.next], { scale: 0, transformOrigin: "50% 50%" });
    gsap.set([...this.gaps, ...this.regions, this.seamLabel, this.pack, this.ripple], { opacity: 0 });
    gsap.set([this.head, this.glow, this.playhead, this.stats], { opacity: 0 });
    for (const count of this.counts) count.textContent = "0";
    this.point.x = marks.learned;
    this.point.y = chart.top;
    this.place();
  }

  /** Puts the travelling point, its glow and the line through it where the story is. */
  private place(): void {
    const { x, y } = this.point;
    this.drawn.setAttribute("width", (x + 1.5).toFixed(2));
    for (const circle of [this.head, this.glow, this.ripple]) {
      circle.setAttribute("cx", x.toFixed(2));
      circle.setAttribute("cy", y.toFixed(2));
    }
    this.playhead.setAttribute("x1", x.toFixed(2));
    this.playhead.setAttribute("x2", x.toFixed(2));
  }

  private story(): gsap.core.Timeline {
    // Tweens set their starting state when they start, not when the story is put together.
    const timeline = gsap.timeline({ defaults: { immediateRender: false } });
    const fading = [this.svg, this.stats];
    this.planned = marks.learned;
    timeline.add(() => this.clear(), 0);
    timeline.fromTo(this.svg, { opacity: 0 }, { opacity: 1, duration: 0.6, ease: "power2.out" }, 0);
    timeline.to([this.head, this.glow, this.playhead], { opacity: 1, duration: 0.6, ease: "power2.out" }, 0.3);

    let t = 0.9;
    reviews.forEach((review, index) => {
      if (review.x > marks.now) return;
      if (review.from < marks.seam && review.x > marks.seam) {
        t = this.travel(timeline, review, marks.seam, t);
        t = this.imported(timeline, t);
      }
      t = this.travel(timeline, review, review.x, t);
      t = this.reviewed(timeline, index, t);
    });

    // On to now, slowing, and the schedule ahead appears.
    const current = reviews.find((review) => review.from < marks.now && review.x >= marks.now);
    if (current != null) t = this.travel(timeline, current, marks.now, t, "power2.out");
    const last = this.gaps.at(-1);
    timeline.to(this.ahead, { attr: { width: marks.next - marks.now + 8 }, duration: 1.4, ease: "power2.inOut" }, t);
    timeline.to(this.next, { scale: 1, duration: 0.8, ease: "back.out(2.4)" }, t + 1.2);
    if (last != null) timeline.fromTo(last, { opacity: 0, y: 6 }, { opacity: 1, y: 0, duration: 0.7 }, t + 1.25);

    // A while to take it in, then the chart breathes out before the story begins again.
    timeline.to(fading, { opacity: 0, duration: 0.9, ease: "power2.inOut" }, t + 5.2);
    return timeline;
  }

  /** The point moves on through time in the gap before `review`, recall falling, up to `to`. */
  private travel(timeline: gsap.core.Timeline, review: Review, to: number, at: number, ease = "none"): number {
    const duration = Math.max(0.05, (to - this.planned) / SPEED);
    this.planned = to;
    timeline.to(
      this.point,
      {
        x: to,
        duration,
        ease,
        onUpdate: () => {
          this.point.y = fall(review, this.point.x);
          this.place();
        },
      },
      at,
    );
    return at + duration;
  }

  /** A review: recall springs back to the top, the review marks the curve, and its gap is named. */
  private reviewed(timeline: gsap.core.Timeline, index: number, at: number): number {
    timeline.to(this.point, { y: chart.top, duration: LIFT, ease: "expo.out", onUpdate: () => this.place() }, at);
    const dot = this.dots[index];
    if (dot != null) timeline.to(dot, { scale: 1, duration: 0.65, ease: "back.out(2.6)" }, at);
    timeline.fromTo(this.glow, { attr: { r: 32 } }, { attr: { r: 18 }, duration: 0.8, ease: "expo.out" }, at);
    const gap = this.gaps[index];
    if (gap != null) timeline.fromTo(gap, { opacity: 0, y: 6 }, { opacity: 1, y: 0, duration: 0.6 }, at + 0.05);
    return at + LIFT + 0.1;
  }

  /**
   * The import: the Anki package drops onto the curve where the point stands, sinks into it, and the moment spreads
   * up and down the chart. The deck's numbers count up as everything arrives.
   */
  private imported(timeline: gsap.core.Timeline, at: number): number {
    const scale = this.svg.getBoundingClientRect().width / chart.width;
    const impact = recall(marks.seam);
    const x = marks.seam * scale - this.pack.offsetWidth / 2;
    const rest = impact * scale - this.pack.offsetHeight - 16;
    timeline.fromTo(
      this.pack,
      { x, y: rest - 90, opacity: 0, rotate: -7, scale: 1.06, filter: "blur(12px)" },
      { y: rest, opacity: 1, rotate: 0, scale: 1, filter: "blur(0px)", duration: 1, ease: "expo.out" },
      at,
    );
    timeline.to(
      this.pack,
      {
        y: impact * scale - this.pack.offsetHeight / 2,
        scale: 0.3,
        opacity: 0,
        filter: "blur(6px)",
        duration: 0.5,
        ease: "power3.in",
      },
      at + 1.15,
    );

    const hit = at + 1.6;
    timeline.fromTo(
      this.ripple,
      { attr: { r: 5 }, opacity: 0.9 },
      { attr: { r: 40 }, opacity: 0, duration: 1.3, ease: "expo.out" },
      hit,
    );
    timeline.fromTo(this.glow, { attr: { r: 44 } }, { attr: { r: 18 }, duration: 1.2, ease: "expo.out" }, hit);
    timeline.to(
      this.seam,
      { attr: { y1: chart.top - 34, y2: chart.base + 26 }, duration: 1, ease: "expo.inOut" },
      hit - 0.1,
    );
    timeline.fromTo(
      this.seamLabel,
      { opacity: 0, y: 8 },
      { opacity: 1, y: 0, duration: 0.8, ease: "expo.out" },
      hit + 0.3,
    );
    this.regions.forEach((region, index) => {
      timeline.fromTo(region, { opacity: 0, y: 6 }, { opacity: 1, y: 0, duration: 0.8 }, hit + 0.45 + index * 0.15);
    });
    timeline.fromTo(
      this.stats,
      { opacity: 0, y: 10, filter: "blur(6px)" },
      { opacity: 1, y: 0, filter: "blur(0px)", duration: 0.9, ease: "expo.out" },
      hit + 0.2,
    );
    this.counts.forEach((count, index) => {
      const value = { n: 0 };
      timeline.to(
        value,
        {
          n: Number(count.dataset.count),
          duration: 1.8,
          ease: "expo.out",
          onUpdate: () => (count.textContent = numbers.format(Math.round(value.n))),
        },
        hit + 0.3 + index * 0.12,
      );
    });
    return hit + 0.6;
  }
}
