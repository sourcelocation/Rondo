import { gsap } from "gsap";
import { Color, type Object3D, OrthographicCamera, Scene, WebGLRenderer } from "three";
import { damp } from "./math";
import { Light } from "./scenery/light";
import type { Screen } from "./screen";
import type { Stage } from "./stage";

/**
 * The screen as the scenery draws it: its size in CSS pixels, with the toolbars away (`height`, which the canvas
 * fills) and how much of it is always seen (`safe`, with them out, motion/screen.ts), the pixels drawn for each, and
 * how far the page is scrolled as the scenery follows it, smoothly, a little behind the page.
 */
export interface View {
  width: number;
  height: number;
  safe: number;
  ratio: number;
  scroll: number;
}

/** Where an element is laid out on the page, in CSS pixels from the page's top left. */
export interface Place {
  readonly left: number;
  readonly top: number;
  readonly width: number;
  readonly height: number;
}

/**
 * Something the scenery draws for a part of the page: the light, the hero's ring, the collection's deck, the flight.
 * Its `object` is drawn into the frame in the window's CSS pixels, in the order of its renderOrder. A picture is a
 * stage of its own, held on the screen: the scroll (`view.scroll`, followed smoothly) only says how far its part of
 * the page has come, which turns, opens, fades or reveals it; nothing in it is pinned to the page's words, which the
 * browser scrolls a frame or so ahead of anything drawn here. A picture off screen hides its object.
 */
export interface Picture {
  readonly object: Object3D;
  /** Moves on by `seconds`, the page as `view` has it; whether the frame has to be drawn again for it. */
  update(view: Readonly<View>, seconds: number): boolean;
  /** Draws whatever it draws offscreen, before the frame (into its own targets). */
  render?(renderer: WebGLRenderer): void;
  /** The window has changed size. */
  resize?(view: Readonly<View>): void;
  /** The GPU was lost and is back: what lived only on it is made again. */
  restore?(renderer: WebGLRenderer): void;
}

/** The most pixels the scenery draws, by the device: a phone's GPU has less to spare. */
const BUDGET = { mouse: 2.6e6, touch: 1.8e6 } as const;
/** The fewest pixels it draws for each CSS pixel, however slow the device. */
const FLOOR = 0.75;
/** How briskly the scenery follows the scroll, per second: about a twentieth of a second behind it. */
const FOLLOW = 18;

/**
 * The scenery behind the page: one WebGL canvas, held behind everything, that draws the page's pictures (the light,
 * the ring around the headline, the collection's deck and the flight) as stages held on the screen. The browser
 * scrolls the page natively (motion/scroll.ts); the scenery follows the scroll smoothly, so however unevenly the
 * scroll reaches the page's script, what's drawn here moves evenly. It draws a frame only when something in it
 * changed; a new size is drawn at once, before the page is painted; and it draws fewer pixels while frames come
 * slowly, more again once there's room. Where WebGL isn't there, or the GPU is lost, the page's CSS light stands in.
 */
export class Scenery {
  readonly light: Light;
  private readonly scene = new Scene();
  /** Unused by the pictures, which place themselves; three.js wants one to draw a scene with. */
  private readonly camera = new OrthographicCamera();
  private readonly pictures: Picture[] = [];
  private readonly view: View = { width: 1, height: 1, safe: 1, ratio: 1, scroll: window.scrollY };
  /** Pixels drawn for each CSS pixel: 0 until the first size sets it. */
  private ratio = 0;
  private ceiling = 1;
  private stale = true;
  private lost = false;
  private slow = 0;
  private quick = 0;
  /** Whether the pointer is a mouse, which can spare more pixels. */
  private readonly fine: boolean;
  private readonly screen: Screen;

  /** The scenery in `host`, for `stage`'s screen, or null where WebGL isn't there. */
  static mount(host: HTMLElement, stage: Stage): Scenery | null {
    const canvas = document.createElement("canvas");
    let renderer: WebGLRenderer;
    try {
      renderer = new WebGLRenderer({
        canvas,
        antialias: false,
        alpha: false,
        depth: false,
        stencil: false,
        powerPreference: "high-performance",
      });
    } catch {
      return null;
    }
    return new Scenery(host, renderer, stage);
  }

  private constructor(
    host: HTMLElement,
    readonly renderer: WebGLRenderer,
    stage: Stage,
  ) {
    this.fine = stage.fine;
    this.screen = stage.screen;
    renderer.setClearColor(new Color(1, 1, 1), 1);
    // The light carries on past the screen's edges, and where there's a mouse it drifts and leans toward it.
    this.light = new Light(stage.fine, stage.pointer, stage.edges);
    this.add(this.light);
    const canvas = renderer.domElement;
    canvas.addEventListener("webglcontextlost", (event) => {
      event.preventDefault();
      this.lost = true;
      host.classList.remove("is-drawn");
    });
    canvas.addEventListener("webglcontextrestored", () => {
      this.lost = false;
      for (const picture of this.pictures) picture.restore?.(renderer);
      this.stale = true;
      host.classList.add("is-drawn");
    });
    host.append(canvas);
    host.classList.add("is-drawn");
    this.resize();
    this.screen.onChange(() => {
      this.resize();
    });
    gsap.ticker.add(this.tick);
  }

  /**
   * Draws `picture` once now, shown or not, so the GPU has built what drawing it takes before it first comes into
   * view: a shader's first use waits until it's built, which would hold up whatever's moving just then.
   */
  warm(picture: Picture): void {
    const { object } = picture;
    const shown = object.visible;
    object.visible = true;
    this.draw();
    object.visible = shown;
    this.stale = true;
  }

  /**
   * Where `element` is laid out on the page, transforms on it and around it aside (the motion's, the curtain's), so a
   * picture keeps to the page's layout whatever moves over it.
   */
  locate(element: HTMLElement): Place {
    let left = 0;
    let top = 0;
    for (let node: Element | null = element; node instanceof HTMLElement; node = node.offsetParent) {
      left += node.offsetLeft;
      top += node.offsetTop;
    }
    return { left, top, width: element.offsetWidth, height: element.offsetHeight };
  }

  /**
   * Draws `picture` from the next frame on. Its shaders are compiled now, while the GPU has time for them, rather
   * than the first time it comes into view, mid-scroll.
   */
  add(picture: Picture): void {
    this.pictures.push(picture);
    this.scene.add(picture.object);
    picture.resize?.(this.view);
    this.renderer.compile(picture.object, this.camera, this.scene);
    this.stale = true;
  }

  private resize(): void {
    const { view, screen } = this;
    view.width = screen.width;
    view.height = screen.height;
    view.safe = screen.safe;
    const budget = this.fine ? BUDGET.mouse : BUDGET.touch;
    this.ceiling = Math.max(
      FLOOR,
      Math.min(window.devicePixelRatio, 2, Math.sqrt(budget / (view.width * view.height))),
    );
    this.apply(this.ratio === 0 ? this.ceiling : Math.min(this.ratio, this.ceiling));
  }

  /** Draws `ratio` pixels for each CSS pixel; a new size clears the canvas, so the frame is drawn again at once. */
  private apply(ratio: number): void {
    const { view, renderer } = this;
    this.ratio = ratio;
    view.ratio = ratio;
    renderer.setPixelRatio(ratio);
    renderer.setSize(view.width, view.height, false);
    for (const picture of this.pictures) picture.resize?.(view);
    for (const picture of this.pictures) picture.update(view, 0);
    this.draw();
  }

  private readonly tick = (_time: number, deltaMs: number): void => {
    if (this.lost) return;
    const seconds = Math.min(deltaMs, 50) / 1000;
    this.follow(seconds);
    let changed = this.stale;
    for (const picture of this.pictures) {
      if (picture.update(this.view, seconds)) changed = true;
    }
    if (!changed) return;
    this.draw();
    this.pace(deltaMs);
  };

  /**
   * Moves the view's scroll toward the page's. A jump of more than a couple of screens (a link to somewhere down the
   * page) is taken at once, rather than sweeping everything between past.
   */
  private follow(seconds: number): void {
    const { view } = this;
    const target = window.scrollY;
    const gap = Math.abs(target - view.scroll);
    view.scroll = gap < 0.25 || gap > view.height * 2 ? target : damp(view.scroll, target, FOLLOW, seconds);
  }

  private draw(): void {
    this.stale = false;
    const { renderer } = this;
    for (const picture of this.pictures) {
      if (picture.object.visible) picture.render?.(renderer);
    }
    renderer.setRenderTarget(null);
    renderer.render(this.scene, this.camera);
  }

  /**
   * Keeps to the screen's pace while drawing: two seconds of slow frames (below about 45 a second) and it draws fewer
   * pixels; five seconds of quick ones, and more again, up to its ceiling.
   */
  private pace(deltaMs: number): void {
    if (deltaMs > 22) {
      this.slow += deltaMs;
      this.quick = 0;
    } else {
      this.quick += deltaMs;
      this.slow = Math.max(0, this.slow - deltaMs * 0.5);
    }
    if (this.slow > 2000 && this.ratio > FLOOR) {
      this.slow = 0;
      this.apply(Math.max(FLOOR, this.ratio * 0.85));
    } else if (this.quick > 5000 && this.ratio < this.ceiling) {
      this.quick = 0;
      this.apply(Math.min(this.ceiling, this.ratio * 1.1));
    }
  }
}
