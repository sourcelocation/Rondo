import { gsap } from "gsap";
import { all } from "./dom";
import { clamp, damp } from "./math";

/** The gap between the pointer and the picture's edge. */
const GAP = 28;
/** The picture's distance from the window's edges, and from its top: below the bar. */
const MARGIN = 16;
const TOP = 88;

/**
 * The maker's projects: hovering one brings its screenshot out of a blur to the left of the pointer, where it
 * trails the pointer a little and leans into its movement, crossfading between projects.
 *
 * It's one continuous state, eased toward its targets every frame, so nothing can fight over it: hovering wants it
 * shown and following; leaving wants it gone, in place. Coming back while it's still mostly visible picks it up
 * where it is; coming back to a faded picture starts it at the pointer.
 */
export class WorkPreview {
  private readonly images: HTMLElement[];
  private hovered: number | null = null;
  private opacity = 0;
  private scale = 0.9;
  private blur = 12;
  private lean = 0;
  private readonly at = { x: 0, y: 0 };
  private readonly pointer = { x: 0, y: 0 };
  private running = false;

  constructor(
    list: HTMLElement,
    private readonly preview: HTMLElement,
    private readonly still: boolean,
  ) {
    this.images = all(preview, ".preview__image");
    all(list, "[data-work-link]").forEach((link, index) => {
      link.addEventListener("pointerenter", (event) => {
        if (event.pointerType === "mouse") this.enter(index, event);
      });
    });
    list.addEventListener("pointerleave", () => (this.hovered = null));
    window.addEventListener(
      "pointermove",
      (event) => {
        this.pointer.x = event.clientX;
        this.pointer.y = event.clientY;
      },
      { passive: true },
    );
  }

  private enter(index: number, event: PointerEvent): void {
    this.pointer.x = event.clientX;
    this.pointer.y = event.clientY;
    this.images.forEach((image, position) => image.classList.toggle("is-current", position === index));
    if (this.opacity < 0.35) {
      const target = this.target();
      this.at.x = target.x;
      this.at.y = target.y;
      this.lean = 0;
    }
    this.hovered = index;
    if (!this.running) {
      this.running = true;
      gsap.ticker.add(this.tick);
    }
  }

  /** Where the picture's corner goes for the pointer: to its left, beside it vertically, inside the window. */
  private target(): { x: number; y: number } {
    const width = this.preview.offsetWidth;
    const height = this.preview.offsetHeight;
    return {
      x: Math.max(MARGIN, this.pointer.x - GAP - width),
      y: clamp(this.pointer.y - height / 2, TOP, window.innerHeight - height - MARGIN),
    };
  }

  private readonly tick = (_time: number, deltaMs: number): void => {
    const seconds = this.still ? 1 : Math.min(deltaMs / 1000, 1 / 20);
    const shown = this.hovered != null;
    this.opacity = damp(this.opacity, shown ? 1 : 0, shown ? 9 : 7, seconds);
    this.scale = damp(this.scale, shown ? 1 : 0.94, 8, seconds);
    this.blur = damp(this.blur, shown ? 0 : 10, 9, seconds);
    if (shown) {
      const target = this.target();
      const lastX = this.at.x;
      this.at.x = damp(this.at.x, target.x, 10, seconds);
      this.at.y = damp(this.at.y, target.y, 10, seconds);
      const velocity = (this.at.x - lastX) / Math.max(seconds, 1 / 240);
      this.lean = this.still ? 0 : damp(this.lean, clamp(velocity * 0.01, -7, 7), 8, seconds);
    } else {
      this.lean = damp(this.lean, 0, 6, seconds);
    }
    this.draw();
    if (!shown && this.opacity < 0.004) {
      this.opacity = 0;
      this.draw();
      this.running = false;
      gsap.ticker.remove(this.tick);
    }
  };

  private draw(): void {
    const style = this.preview.style;
    style.transform =
      `translate3d(${this.at.x.toFixed(1)}px, ${this.at.y.toFixed(1)}px, 0) ` +
      `rotate(${this.lean.toFixed(2)}deg) scale(${this.scale.toFixed(3)})`;
    style.opacity = this.opacity.toFixed(3);
    style.filter = this.blur > 0.1 ? `blur(${this.blur.toFixed(1)}px)` : "none";
  }
}
