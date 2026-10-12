import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import type { Mesh } from "three";
import { ring } from "../../content/cards";
import { one, onResize } from "../dom";
import { TAU, clamp, damp } from "../math";
import type { Picture, Place, Scenery, View } from "../scenery";
import type { Stage } from "../stage";
import { Cards, type Look } from "./cards";

/** The ring's cards, as their CSS had them: glass, whiter at the top, over a long soft shadow. */
const LOOK: Look = {
  fill: { angle: 165, from: 0.92, to: 0.66, stop: 0, tint: [1, 1, 1] },
  edge: 0.05,
  shadows: [
    { y: 18, blur: 40, spread: -22, alpha: 0.3 },
    { y: 2, blur: 6, spread: 0, alpha: 0.05 },
  ],
};

/** How far the ring rises as the hero scrolls away, as a share of the hero's height: it's further off than the words. */
const RISE = 0.3;

/** Where the camera starts as the page opens, in the ring's middle: this far back from it, in the ring's radii. */
const INSIDE = 0.2;

/** A card of the ring: its face, its place on it, and where it's drawn this frame. */
interface Slot {
  readonly face: number;
  /** Its place on the ring, before the ring turns. */
  angle: number;
  /** From the ring's middle, in CSS pixels. */
  x: number;
  y: number;
  scale: number;
  /** How far it's turned about its upright, in radians. */
  turn: number;
  blur: number;
  opacity: number;
  /** -1 at the back of the ring, 1 at its front: the order the cards are drawn in. */
  near: number;
}

/**
 * The flashcards circling the hero's headline, drawn behind it by the scenery: a ring seen from a little above, in
 * perspective, turning once every ninety seconds (a little faster while the page scrolls) and leaning toward a mouse.
 * Like a camera focused on the headline, cards at its depth are sharp, while those behind it and those passing in
 * front blur out of focus; the focus racks back as the mouse rises and forward as it falls. It's held on the screen
 * round where the headline stands; the page opens with the camera in its middle, pulling back out through it.
 * Scrolling away, the camera flies back in through it as the headline leaves, the ring rising a little and fading,
 * its focus as it was. Its cards are the size and type of a sizer in the hero, which the page lays out but doesn't show.
 */
export class CardRing implements Picture {
  readonly object: Mesh;
  /**
   * Resolves once the camera can pull back through the ring without holding anything up: its faces drawn, and the GPU
   * ready for it.
   */
  readonly ready: Promise<void>;
  private readonly cards: Cards;
  private readonly slots: Slot[] = ring.map((_, face) => ({
    face,
    angle: 0,
    x: 0,
    y: 0,
    scale: 1,
    turn: 0,
    blur: 0,
    opacity: 0,
    near: 0,
  }));
  private active: Slot[] = [];
  private hero: Place = { left: 0, top: 0, width: 1, height: 1 };
  private spin = 0;
  private radius = 0;
  private focal = 0;
  /** How far the camera has pulled back, from the ring's middle (0) to where it rests (1). */
  private readonly camera = { back: 0 };
  /** How far the ring leans back: more on phones, so its front passes under the headline rather than over it. */
  private lean = 0;
  /** The ring's middle, from the hero's corner: the headline's. */
  private centreX = 0;
  private centreY = 0;
  /** The depth in focus, from -1 (the back of the ring) to 1 (its front); the headline is at 0. */
  private focus = 0;
  private readonly lookAt = { x: 0, y: 0, targetX: 0, targetY: 0 };

  constructor(
    private readonly scenery: Scenery,
    private readonly root: HTMLElement,
    private readonly title: HTMLElement,
    private readonly stage: Stage,
  ) {
    this.cards = new Cards(scenery.renderer, ring.length, LOOK, 900);
    this.object = this.cards.mesh;
    this.object.renderOrder = 1;
    const sizer = one(root, "[data-ring-sizer]");
    const faces = ring.map((card) => ({ card: { ...card, back: card.detail ?? card.back }, sizer }));
    const measure = () => {
      this.measure();
      void this.cards.measure(faces);
    };
    this.measure();
    // The hero changing size moves the ring's middle, and so does what's around the headline changing, or anything
    // above the hero.
    for (const element of [root, title.parentElement ?? root, sizer]) onResize(element, measure);
    ScrollTrigger.addEventListener("refresh", measure);
    scenery.add(this);
    this.ready = this.cards.measure(faces).then(() => {
      this.cards.warm();
      scenery.warm(this);
    });
  }

  /**
   * The camera pulls back out of the ring's middle to where it rests: the cards it passes come in from the edges,
   * huge and out of focus as they go by close to it, and shrink into their places.
   */
  pullBack(): gsap.core.Tween {
    return gsap.to(this.camera, { back: 1, duration: 3.5, ease: "power3.out" });
  }

  update(view: Readonly<View>, seconds: number): boolean {
    const exit = clamp((view.scroll - this.hero.top) / this.hero.height, 0, 1);
    if (exit >= 1) {
      const was = this.object.visible;
      this.object.visible = false;
      return was;
    }
    this.object.visible = true;
    // A mouse's, not a finger's: a finger moves only to scroll, and the ring would lurch toward it each time.
    const { pointer } = this.stage;
    if (pointer.mouse) {
      this.lookAt.targetX = pointer.x;
      this.lookAt.targetY = pointer.y;
    }
    this.spin += seconds * (TAU / 90 + Math.min(Math.abs(this.stage.scroll.velocity), 60) * 0.0025);
    this.lookAt.x = damp(this.lookAt.x, this.lookAt.targetX, 2.2, seconds);
    this.lookAt.y = damp(this.lookAt.y, this.lookAt.targetY, 2.2, seconds);
    this.focus = damp(this.focus, this.lookAt.targetY * 0.7, 1.4, seconds);
    const y = this.hero.top + this.centreY - exit * this.hero.height * RISE;
    this.draw(this.hero.left + this.centreX, y, exit);
    return true;
  }

  resize(view: Readonly<View>): void {
    this.cards.setViewport(view.width, view.height);
  }

  private measure(): void {
    const width = window.innerWidth;
    const narrow = width < 760;
    const count = narrow ? 8 : this.slots.length;
    this.active = this.slots.slice(0, count);
    this.active.forEach((slot, index) => (slot.angle = (index / count) * TAU));
    this.radius = narrow ? width * 0.5 : clamp(width * 0.39, 380, 640);
    this.lean = narrow ? 0.55 : 0.29;
    this.focal = this.radius * 2.8;
    this.hero = this.scenery.locate(this.root);
    this.centreX = this.hero.width / 2;
    this.centreY = this.scenery.locate(this.title).top - this.hero.top + this.title.offsetHeight * 0.52;
  }

  /**
   * Draws the ring round (`x`, `y`) on the window, `exit` of the way to leaving, back to front. Leaving, the camera
   * flies back in through it, the way it came out, and the ring fades over the second half of the way.
   */
  private draw(x: number, y: number, exit: number): void {
    const stand = this.lean + this.lookAt.y * 0.12;
    const yaw = this.lookAt.x * 0.35;
    const inside = this.radius * INSIDE;
    const pulled = inside + (this.focal - inside) * this.camera.back;
    const distance = pulled + (inside - pulled) * exit;
    const shown = clamp((1 - exit) / 0.6, 0, 1);
    for (const slot of this.active) this.place(slot, stand, yaw, distance, shown);
    this.cards.begin();
    for (const slot of [...this.active].sort((a, b) => a.near - b.near)) {
      this.cards.add({ ...slot, x: x + slot.x, y: y + slot.y, roll: 0 });
    }
    this.cards.end();
  }

  /**
   * Where a card is on the ring, which leans back by `stand`, seen by a camera `distance` back from its middle; `shown`
   * of the way to fully there.
   */
  private place(slot: Slot, stand: number, yaw: number, distance: number, shown: number): void {
    const angle = slot.angle + this.spin + yaw;
    const x = Math.sin(angle) * this.radius;
    const z = Math.cos(angle) * this.radius;
    // How far in front of the camera the card is, in the ring's radii: behind it, it isn't drawn.
    const ahead = (distance - z * Math.cos(stand)) / this.radius;
    slot.scale = this.focal / (Math.max(ahead, 0.01) * this.radius);
    slot.x = x * slot.scale;
    slot.y = z * Math.sin(stand) * slot.scale;
    // -1 at the back of the ring, 1 at its front; the headline sits at 0. Taken from the ring as it stands, so the
    // camera moving leaves the cards' focus, and their order, as they were.
    const near = Math.cos(angle) * Math.cos(stand);
    const off = near - this.focus;
    const defocus = off < 0 ? -off * 7 : Math.max(0, off - 0.22) * 14;
    // Going by close to the camera, it's out of focus, and it fades in as it comes out from behind it.
    slot.blur = defocus + Math.max(0, 1 - ahead) * 4;
    const presence = near < 0 ? 1 + near * 0.5 : 1 - Math.max(0, near - 0.3) * 0.55;
    slot.opacity = clamp((ahead - 0.12) / 0.3, 0, 1) * presence * shown;
    slot.near = near;
    // Each card turns with the ring's curve, most at its sides, facing out the way the ring bends there.
    slot.turn = (Math.sin(angle) * 38 * Math.PI) / 180;
  }
}
