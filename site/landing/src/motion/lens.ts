import { gsap } from "gsap";
import { one, onResize } from "./dom";
import { whileOnScreen } from "./loop";
import { clamp, damp, lerp, progress } from "./math";
import type { Pointer } from "./pointer";

/** How much the lens magnifies. */
const MAGNIFY = 1.14;

/** How far from the code, in pixels, the pointer starts to draw the lens toward it. */
const REACH = 520;

/** How much bigger the lens grows while the pointer is over the code. */
const GROW = 0.35;

/**
 * The open-source section's lens: a round window over blurred code that holds a sharp, slightly magnified copy of
 * it. It wanders over the code by itself, and is drawn from its path toward the pointer the nearer the pointer comes,
 * until over the code it follows it, with a little lag, grown larger, while the panel dims a little under it; it
 * swells as it moves. On touch screens it only wanders.
 */
export class CodeLens {
  private readonly window: HTMLElement;
  private readonly sharp: HTMLElement;
  private radius = 150;
  private width = 0;
  private height = 0;
  private readonly at = { x: 0, y: 0 };
  private readonly target = { x: 0, y: 0 };
  private swell = 0;
  /** How far grown it is for the pointer over the code, from 0 to 1. */
  private grown = 0;
  private time = Math.random() * 100;

  constructor(
    private readonly root: HTMLElement,
    private readonly pointer: Pointer,
    still: boolean,
  ) {
    this.window = one(root, "[data-lens-window]");
    this.sharp = one(root, "[data-lens-sharp]");
    const soft = one(root, ".lens__code--soft");
    this.sharp.replaceChildren(...[...soft.children].map((child) => child.cloneNode(true)));
    this.measure();
    this.at.x = this.target.x = this.width * 0.5;
    this.at.y = this.target.y = this.height * 0.45;
    onResize(root, () => {
      this.measure();
      this.draw();
    });
    // Without motion it doesn't wander, and simply sits where the pointer is over the code, grown at once.
    if (still) {
      root.addEventListener("pointermove", (event) => {
        if (event.pointerType !== "mouse") return;
        const box = root.getBoundingClientRect();
        this.at.x = event.clientX - box.left;
        this.at.y = event.clientY - box.top;
        this.hover(true);
        this.grown = 1;
        this.draw();
      });
      root.addEventListener("pointerleave", () => {
        this.hover(false);
        this.grown = 0;
        this.draw();
      });
    }
    gsap.to(this.window, { opacity: 1, duration: 1.2, ease: "power2.out" });
    this.draw();
    if (!still) whileOnScreen(root, this.tick);
  }

  private measure(): void {
    this.width = this.root.clientWidth;
    this.height = this.root.clientHeight;
    this.radius = clamp(this.width * 0.11, 96, 160);
    const size = `${this.radius * 2}px`;
    this.window.style.width = size;
    this.window.style.height = size;
    this.sharp.style.width = `${this.width}px`;
    this.sharp.style.height = `${this.height}px`;
  }

  private readonly tick = (_time: number, deltaMs: number): void => {
    const seconds = Math.min(deltaMs / 1000, 1 / 20);
    this.time += seconds;
    // Wandering: a slow figure that never quite repeats.
    const wanderX = this.width * (0.5 + 0.34 * Math.sin(this.time * 0.31) * Math.cos(this.time * 0.11));
    const wanderY = this.height * (0.48 + 0.3 * Math.sin(this.time * 0.47 + 1.3));
    // Drawn toward the nearest point of the code to the pointer, the more the nearer it is.
    let pull = 0;
    let inside = false;
    let pointerX = wanderX;
    let pointerY = wanderY;
    if (this.pointer.mouse) {
      const box = this.root.getBoundingClientRect();
      const x = this.pointer.clientX - box.left;
      const y = this.pointer.clientY - box.top;
      pointerX = clamp(x, 0, this.width);
      pointerY = clamp(y, 0, this.height);
      const away = Math.hypot(x - pointerX, y - pointerY);
      const near = 1 - progress(away, 0, REACH);
      pull = near * near * (3 - 2 * near);
      inside = away === 0;
    }
    this.hover(inside);
    this.grown = damp(this.grown, inside ? 1 : 0, 7, seconds);
    this.target.x = lerp(wanderX, pointerX, pull);
    this.target.y = lerp(wanderY, pointerY, pull);
    const lastX = this.at.x;
    const lastY = this.at.y;
    const rate = lerp(2.5, 9, pull);
    this.at.x = damp(this.at.x, this.target.x, rate, seconds);
    this.at.y = damp(this.at.y, this.target.y, rate, seconds);
    const speed = Math.hypot(this.at.x - lastX, this.at.y - lastY) / Math.max(seconds, 1 / 240);
    this.swell = damp(this.swell, clamp(speed / 2400, 0, 0.09), 6, seconds);
    this.draw();
  };

  /** Marks the panel as having the pointer over it, which dims it (in the styles). */
  private hover(inside: boolean): void {
    this.root.classList.toggle("is-hovered", inside);
  }

  private draw(): void {
    const { radius } = this;
    const { x, y } = this.at;
    const swell = (1 + this.swell) * (1 + GROW * this.grown);
    this.window.style.transform = `translate3d(${(x - radius).toFixed(2)}px, ${(y - radius).toFixed(2)}px, 0) scale(${swell.toFixed(4)})`;
    // The copy inside moves the other way, so the point under the lens's centre stays put, magnified about it.
    const scale = MAGNIFY / swell;
    this.sharp.style.transform = `translate3d(${(radius - x * scale).toFixed(2)}px, ${(radius - y * scale).toFixed(2)}px, 0) scale(${scale.toFixed(4)})`;
  }
}
