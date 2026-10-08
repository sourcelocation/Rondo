import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { all, one } from "./dom";

/**
 * A row of chips drifting sideways without end. It drifts faster for a moment while the page scrolls, in the
 * direction the page goes, and rests when off screen.
 */
export class Marquee {
  private readonly track: HTMLElement;
  private readonly tracks: HTMLElement[];
  private offset = 0;
  private boost = 0;
  private width = 0;

  constructor(
    row: HTMLElement,
    private readonly direction: 1 | -1,
    private readonly scrollSpeed: () => number,
  ) {
    this.track = one(row, "[data-marquee-track]");
    // Copies of the track follow it, enough to fill the row at any width.
    for (let copy = 0; copy < 3; copy++) {
      const clone = this.track.cloneNode(true) as HTMLElement;
      clone.setAttribute("aria-hidden", "true");
      row.append(clone);
    }
    this.tracks = all(row, "[data-marquee-track]");
    this.measure();
    window.addEventListener("resize", () => this.measure());
    ScrollTrigger.create({
      trigger: row,
      start: "top bottom",
      end: "bottom top",
      onToggle: ({ isActive }) => {
        if (isActive) gsap.ticker.add(this.tick);
        else gsap.ticker.remove(this.tick);
      },
    });
  }

  private measure(): void {
    this.width = this.track.offsetWidth;
  }

  private readonly tick = (_time: number, deltaMs: number): void => {
    const seconds = Math.min(deltaMs / 1000, 1 / 20);
    const speed = this.scrollSpeed();
    this.boost += (speed * 0.9 - this.boost) * Math.min(1, seconds * 4);
    this.offset += (28 + Math.abs(this.boost) * 10) * seconds * this.direction * (this.boost < -0.5 ? -1 : 1);
    const x = this.width > 0 ? -(((this.offset % this.width) + this.width) % this.width) : 0;
    for (const track of this.tracks) track.style.transform = `translate3d(${x.toFixed(2)}px, 0, 0)`;
  };
}
