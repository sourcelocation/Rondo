import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { all } from "./dom";
import { TAU, damp, quantize } from "./math";

/** The core's layer: marks behind the ring's middle sort under it, marks in front over it. */
const CORE_LAYER = 100;

/**
 * The platforms' marks circling Rondo's, on a ring seen from a little above. On arrival the ring spins in, slows,
 * and comes to rest with the visitor's platform at the front, its name showing; then it breathes, and leans with
 * the pointer. Marks at the back blur and dim, as the hero's cards do.
 */
export class Orbit {
  private readonly items: HTMLElement[];
  private readonly state = { spin: 0 };
  private readonly rest: number;
  private readonly pointer = { x: 0, y: 0, targetX: 0, targetY: 0 };
  private radius = 0;
  private time = 0;

  constructor(
    private readonly root: HTMLElement,
    os: string,
    private readonly still: boolean,
  ) {
    this.items = all(root, "[data-orbit-item]");
    const yours = Math.max(
      0,
      this.items.findIndex((item) => (item.dataset.for ?? "").split(" ").includes(os)),
    );
    this.items[yours]?.classList.add("is-yours");
    // Item i sits at angle i/n of a turn plus the spin; the front is angle 0.
    this.rest = -(yours / this.items.length) * TAU;
    root.classList.add("is-moving");
    this.measure();
    window.addEventListener("resize", () => {
      this.measure();
      if (this.still) this.draw();
    });

    if (still) {
      this.state.spin = this.rest;
      this.draw();
      return;
    }
    this.state.spin = this.rest - TAU * 1.15;
    this.draw();
    window.addEventListener(
      "pointermove",
      (event) => {
        this.pointer.targetX = event.clientX / window.innerWidth - 0.5;
        this.pointer.targetY = event.clientY / window.innerHeight - 0.5;
      },
      { passive: true },
    );
    gsap.to(this.state, { spin: this.rest, duration: 4.2, ease: "expo.out", delay: 0.3 });
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

  /** The ring's radius: wide on large screens, and kept clear of a phone's edges. */
  private measure(): void {
    const width = this.root.clientWidth;
    this.radius = Math.min(width * (width < 600 ? 0.32 : 0.42), 330);
  }

  private readonly tick = (_time: number, deltaMs: number): void => {
    const seconds = Math.min(deltaMs / 1000, 1 / 20);
    this.time += seconds;
    this.pointer.x = damp(this.pointer.x, this.pointer.targetX, 2, seconds);
    this.pointer.y = damp(this.pointer.y, this.pointer.targetY, 2, seconds);
    this.draw();
  };

  private draw(): void {
    const breath = this.still ? 0 : Math.sin(this.time * 0.7) * 0.05;
    const spin = this.state.spin + breath + this.pointer.x * 0.5;
    const tilt = 0.34 + this.pointer.y * 0.16;
    const focal = this.radius * 3;
    this.items.forEach((item, index) => {
      const angle = (index / this.items.length) * TAU + spin;
      const x = Math.sin(angle) * this.radius;
      const z = Math.cos(angle) * this.radius;
      const depth = z * Math.cos(tilt);
      const scale = focal / (focal - depth);
      const y = z * Math.sin(tilt) * scale;
      // -1 at the back, 1 at the front.
      const near = depth / this.radius;
      const blur = quantize(Math.max(0, -near) * 5, 0.25);
      const front = Math.max(0, Math.cos(angle));
      const style = item.style;
      style.transform = `translate3d(${(x * scale).toFixed(2)}px, ${y.toFixed(2)}px, 0) scale(${scale.toFixed(4)})`;
      style.filter = blur > 0.2 ? `blur(${String(blur)}px)` : "none";
      style.opacity = (0.45 + 0.55 * ((near + 1) / 2)).toFixed(3);
      style.zIndex = String(CORE_LAYER + Math.round(near * 50));
      const label = item.lastElementChild;
      if (label instanceof HTMLElement) label.style.opacity = (front ** 8).toFixed(3);
    });
  }
}
