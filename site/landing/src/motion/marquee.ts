import { all, one, onResize } from "./dom";
import { whileOnScreen } from "./loop";
import { clamp, quantize } from "./math";

/** How far in from each end of the row its chips fade in, as a share of its width. */
const FADE = 0.14;

/** A chip in the row: where it sits in it, before the drift, and how much it shows. */
interface Chip {
  readonly element: HTMLElement;
  readonly track: HTMLElement;
  left: number;
  width: number;
  opacity: number;
}

/**
 * A row of chips drifting sideways without end, fading in and out at its ends. It drifts faster for a moment while
 * the page scrolls, in the direction the page goes, and rests when off screen. Each chip fades as a whole, by where it
 * is: a mask over the row would have the screen mask it afresh on every frame it drifts.
 */
export class Marquee {
  private readonly track: HTMLElement;
  private readonly tracks: HTMLElement[];
  private chips: Chip[] = [];
  private offset = 0;
  private boost = 0;
  private width = 0;
  private span = 0;

  constructor(
    private readonly row: HTMLElement,
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
    this.chips = this.tracks.flatMap((track) =>
      [...track.children]
        .filter((child) => child instanceof HTMLElement)
        .map((element) => ({ element, track, left: 0, width: 0, opacity: 1 })),
    );
    this.measure();
    onResize(this.track, () => this.measure());
    onResize(row, () => this.measure());
    whileOnScreen(row, this.tick);
  }

  private measure(): void {
    this.width = this.track.offsetWidth;
    this.span = this.row.clientWidth;
    for (const chip of this.chips) {
      // Its track's place in the row, and its own in the track: a transformed track is what its offsets count from.
      const inTrack = chip.element.getBoundingClientRect().left - chip.track.getBoundingClientRect().left;
      chip.left = chip.track.offsetLeft + inTrack;
      chip.width = chip.element.offsetWidth;
    }
  }

  private readonly tick = (_time: number, deltaMs: number): void => {
    const seconds = Math.min(deltaMs / 1000, 1 / 20);
    const speed = this.scrollSpeed();
    this.boost += (speed * 0.9 - this.boost) * Math.min(1, seconds * 4);
    this.offset += (28 + Math.abs(this.boost) * 10) * seconds * this.direction * (this.boost < -0.5 ? -1 : 1);
    const x = this.width > 0 ? -(((this.offset % this.width) + this.width) % this.width) : 0;
    for (const track of this.tracks) track.style.transform = `translate3d(${x.toFixed(2)}px, 0, 0)`;
    const fade = this.span * FADE;
    for (const chip of this.chips) {
      const middle = chip.left + x + chip.width / 2;
      const opacity = quantize(clamp(Math.min(middle, this.span - middle) / fade, 0, 1), 0.02);
      if (opacity === chip.opacity) continue;
      chip.opacity = opacity;
      chip.element.style.opacity = String(opacity);
    }
  };
}
