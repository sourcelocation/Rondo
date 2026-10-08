import { gsap } from "gsap";
import { all } from "./dom";
import { TAU, clamp, damp, quantize } from "./math";

interface Slot {
  readonly element: HTMLElement;
  /** Its place on the ring, before the ring turns. */
  angle: number;
  /** 0 while gathered at the centre, 1 once dealt into place. */
  dealt: number;
  blur: number;
  opacity: number;
  layer: number;
}

/** The headline's layer: cards behind the ring's middle sort under it, cards in front over it. */
const TITLE_LAYER = 1000;

/**
 * The flashcards circling the hero's headline: a ring seen from a little above, in perspective, turning once every
 * ninety seconds (a little faster while the page scrolls) and leaning toward the pointer. Like a camera focused on
 * the headline, cards at its depth are sharp, while those behind it and those passing in front blur out of focus;
 * the focus racks back as the pointer rises and forward as it falls. Scrolling away, the ring opens wide and fades.
 */
export class CardRing {
  private readonly slots: Slot[];
  private active: Slot[] = [];
  private spin = 0;
  private radius = 0;
  private focal = 0;
  /** How far the ring leans back: more on phones, so its front passes under the headline rather than over it. */
  private lean = 0;
  private centreX = 0;
  private centreY = 0;
  private exit = 0;
  /** The depth in focus, from -1 (the back of the ring) to 1 (its front); the headline is at 0. */
  private focus = 0;
  private readonly pointer = { x: 0, y: 0, targetX: 0, targetY: 0 };
  private running = false;

  constructor(
    private readonly root: HTMLElement,
    private readonly title: HTMLElement,
    private readonly scrollSpeed: () => number,
    private readonly still: boolean,
  ) {
    this.slots = all(root, "[data-ring-card]").map((element) => ({
      element,
      angle: 0,
      dealt: 0,
      blur: -1,
      opacity: -1,
      layer: -1,
    }));
    this.measure();
    window.addEventListener("resize", () => {
      this.measure();
      if (this.still) this.draw();
    });
    if (!still) {
      window.addEventListener(
        "pointermove",
        (event) => {
          this.pointer.targetX = event.clientX / window.innerWidth - 0.5;
          this.pointer.targetY = event.clientY / window.innerHeight - 0.5;
        },
        { passive: true },
      );
    }
  }

  /** Deals the cards out of the middle into their places, one after another. */
  deal(): gsap.core.Tween {
    return gsap.to(this.active, { dealt: 1, duration: 2.8, ease: "expo.out", stagger: 0.07 });
  }

  /** Puts every card in its place at once. */
  place(): void {
    for (const slot of this.slots) slot.dealt = 1;
    this.draw();
  }

  /** How far the hero has scrolled away, from 0 to 1. */
  set leaving(progress: number) {
    this.exit = progress;
    if (this.still) this.draw();
  }

  /** Turns the ring while it's on screen. */
  set visible(visible: boolean) {
    if (this.still || visible === this.running) return;
    this.running = visible;
    if (visible) gsap.ticker.add(this.tick);
    else gsap.ticker.remove(this.tick);
  }

  private measure(): void {
    const width = window.innerWidth;
    const narrow = width < 760;
    const count = narrow ? 8 : this.slots.length;
    this.active = this.slots.slice(0, count);
    this.slots.forEach((slot, index) => {
      slot.angle = (index / count) * TAU;
      slot.element.style.display = index < count ? "" : "none";
    });
    this.radius = narrow ? width * 0.5 : clamp(width * 0.39, 380, 640);
    this.lean = narrow ? 0.55 : 0.29;
    this.focal = this.radius * 2.8;
    // The ring circles the headline's middle; offsets ignore the transforms the motion gives the headline.
    const centre = this.title.offsetParent;
    const top = centre instanceof HTMLElement ? centre.offsetTop : 0;
    this.centreX = this.root.clientWidth / 2;
    this.centreY = top + this.title.offsetTop + this.title.offsetHeight * 0.52;
  }

  private readonly tick = (_time: number, deltaMs: number): void => {
    const seconds = Math.min(deltaMs / 1000, 1 / 20);
    this.spin += seconds * (TAU / 90 + Math.min(Math.abs(this.scrollSpeed()), 60) * 0.0025);
    this.pointer.x = damp(this.pointer.x, this.pointer.targetX, 2.2, seconds);
    this.pointer.y = damp(this.pointer.y, this.pointer.targetY, 2.2, seconds);
    this.focus = damp(this.focus, this.pointer.targetY * 0.7, 1.4, seconds);
    this.draw();
  };

  private draw(): void {
    const tilt = this.lean + this.pointer.y * 0.12 + this.exit * 0.45;
    const yaw = this.pointer.x * 0.35;
    for (const slot of this.active) this.drawCard(slot, tilt, yaw);
  }

  private drawCard(slot: Slot, tilt: number, yaw: number): void {
    const { dealt } = slot;
    const angle = slot.angle + this.spin + yaw + (1 - dealt) * 1.8;
    const radius = this.radius * (0.12 + 0.88 * dealt) * (1 + this.exit * 0.6);
    const x = Math.sin(angle) * radius;
    const z = Math.cos(angle) * radius;
    const depth = z * Math.cos(tilt);
    const scale = this.focal / (this.focal - depth);
    const y = z * Math.sin(tilt) * scale + this.exit * this.radius * 0.25;
    // -1 at the back of the ring, 1 at its front; the headline sits at 0.
    const near = depth / this.radius;
    const off = near - this.focus;
    const defocus = off < 0 ? -off * 7 : Math.max(0, off - 0.22) * 14;
    const blur = quantize(defocus + (1 - dealt) * 16 + this.exit * 12, 0.25);
    const presence = near < 0 ? 1 + near * 0.5 : 1 - Math.max(0, near - 0.3) * 0.55;
    const opacity = quantize(dealt * presence * (1 - this.exit), 0.01);
    const layer = near > 0 ? TITLE_LAYER + 1 + Math.round(near * 100) : TITLE_LAYER - 1 + Math.round(near * 100);

    // Each card turns with the ring's curve, most at its sides, facing out the way the ring bends there.
    const turn = Math.sin(angle) * 38;

    const style = slot.element.style;
    style.transform =
      `translate3d(${(this.centreX + x * scale).toFixed(2)}px, ${(this.centreY + y).toFixed(2)}px, 0) ` +
      `scale(${scale.toFixed(4)}) perspective(900px) rotateY(${turn.toFixed(2)}deg)`;
    if (blur !== slot.blur) {
      style.filter = blur > 0.2 ? `blur(${blur}px)` : "none";
      slot.blur = blur;
    }
    if (opacity !== slot.opacity) {
      style.opacity = String(opacity);
      slot.opacity = opacity;
    }
    if (layer !== slot.layer) {
      style.zIndex = String(layer);
      slot.layer = layer;
    }
  }
}
