import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { one, onResize } from "./dom";
import { clamp, damp } from "./math";

/** How much the lens magnifies. */
const MAGNIFY = 1.14;

/**
 * The open-source section's lens: a round window over blurred code that holds a sharp, slightly magnified copy of
 * it. It follows the pointer with a little lag and swells as it moves; on touch screens, and while the pointer is
 * elsewhere, it wanders over the code by itself.
 */
export class CodeLens {
  private readonly window: HTMLElement;
  private readonly sharp: HTMLElement;
  private radius = 150;
  private width = 0;
  private height = 0;
  private readonly at = { x: 0, y: 0 };
  private readonly target = { x: 0, y: 0 };
  private following = false;
  private swell = 0;
  private time = Math.random() * 100;

  constructor(
    private readonly root: HTMLElement,
    private readonly still: boolean,
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
    root.addEventListener("pointermove", (event) => {
      if (event.pointerType !== "mouse") return;
      const box = root.getBoundingClientRect();
      this.target.x = event.clientX - box.left;
      this.target.y = event.clientY - box.top;
      this.following = true;
      if (this.still) {
        this.at.x = this.target.x;
        this.at.y = this.target.y;
        this.draw();
      }
    });
    root.addEventListener("pointerleave", () => (this.following = false));
    gsap.to(this.window, { opacity: 1, duration: 1.2, ease: "power2.out" });
    this.draw();
    if (still) return;
    ScrollTrigger.create({
      trigger: root,
      start: "top bottom",
      end: "bottom top",
      onToggle: ({ isActive }) => {
        if (isActive) gsap.ticker.add(this.tick);
        else gsap.ticker.remove(this.tick);
      },
    });
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
    if (!this.following) {
      // Wandering: a slow figure that never quite repeats.
      this.target.x = this.width * (0.5 + 0.34 * Math.sin(this.time * 0.31) * Math.cos(this.time * 0.11));
      this.target.y = this.height * (0.48 + 0.3 * Math.sin(this.time * 0.47 + 1.3));
    }
    const lastX = this.at.x;
    const lastY = this.at.y;
    const rate = this.following ? 9 : 2.5;
    this.at.x = damp(this.at.x, this.target.x, rate, seconds);
    this.at.y = damp(this.at.y, this.target.y, rate, seconds);
    const speed = Math.hypot(this.at.x - lastX, this.at.y - lastY) / Math.max(seconds, 1 / 240);
    this.swell = damp(this.swell, clamp(speed / 2400, 0, 0.09), 6, seconds);
    this.draw();
  };

  private draw(): void {
    const { radius } = this;
    const { x, y } = this.at;
    const swell = 1 + this.swell;
    this.window.style.transform = `translate3d(${(x - radius).toFixed(2)}px, ${(y - radius).toFixed(2)}px, 0) scale(${swell.toFixed(4)})`;
    // The copy inside moves the other way, so the point under the lens's centre stays put, magnified about it.
    const scale = MAGNIFY / swell;
    this.sharp.style.transform = `translate3d(${(radius - x * scale).toFixed(2)}px, ${(radius - y * scale).toFixed(2)}px, 0) scale(${scale.toFixed(4)})`;
  }
}
