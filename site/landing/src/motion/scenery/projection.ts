import { ScrollTrigger } from "gsap/ScrollTrigger";
import {
  type IUniform,
  Mesh,
  PlaneGeometry,
  ShaderMaterial,
  type Texture,
  Vector2,
  Vector4,
  type WebGLRenderer,
} from "three";
import { one, onResize } from "../dom";
import { Film, type FilmOptions } from "../flight/film";
import type { Pointer } from "../pointer";
import type { Picture, Place, Scenery, View } from "../scenery";

/** The most pixels the film draws a frame in: fewer for a light film. Never more than two for each CSS pixel. */
const BUDGET = { full: 2.4e6, light: 1.3e6 } as const;

const vertex = /* glsl */ `
  uniform vec4 uRect;
  uniform vec2 uViewport;
  varying vec2 vUv;

  void main() {
    vUv = position.xy * 0.5 + 0.5;
    vec2 at = uRect.xy + vec2(vUv.x, 1.0 - vUv.y) * uRect.zw;
    gl_Position = vec4(at / uViewport * vec2(2.0, -2.0) + vec2(-1.0, 1.0), 0.0, 1.0);
  }
`;

/**
 * The film's frame, faded into the page at the top and the foot of the section's stage (its top and height on the
 * screen, uStage), as the poster's mask is (uFade, its stops): under the words above it, and into what follows, its
 * foot fading on past the stage's, so the page below comes clear only as the section goes on. Its top fade comes in
 * with the stage, the edge of the page above, but stays at the screen's top as the stage goes: only the film and its
 * foot go on up.
 */
const fragment = /* glsl */ `
  uniform sampler2D uFrame;
  uniform vec4 uRect;
  uniform vec2 uStage;
  uniform vec4 uFade;
  varying vec2 vUv;

  void main() {
    float y = (1.0 - vUv.y) * uRect.w;
    float down = (y - uStage.x) / uStage.y;
    float fromTop = (y - max(uStage.x, 0.0)) / uStage.y;
    float shown = clamp((fromTop - uFade.x) / (uFade.y - uFade.x), 0.0, 1.0)
      * clamp((uFade.w - down) / (uFade.w - uFade.z), 0.0, 1.0);
    gl_FragColor = vec4(texture(uFrame, vUv).rgb * shown, shown);
  }
`;

/**
 * The flight's film (motion/flight/film.ts), shown by the scenery as a screen of its own, held still: the flight
 * section's stage holds the screen while the section scrolls past (it's sticky), and as it comes and goes the film
 * stays where it is, revealed and covered by the soft edges the poster's mask has, which follow the stage. The film is
 * drawn again only when it changes; between, the scenery shows its last frame. It draws its first frame as soon as
 * it's made, and the section is marked playing (.is-playing), so its poster has given way before the section comes.
 * Should the GPU be lost, the poster comes back while the film is made again.
 */
export class Projection implements Picture {
  readonly object: Mesh;
  private film: Film | null = null;
  private readonly stage: HTMLElement;
  /** The film's box in the stage, whose mask's stops the film fades by. */
  private readonly box: HTMLElement;
  private readonly rect = new Vector4();
  private readonly stageAt = new Vector2(0, 1);
  private readonly fade = new Vector4(0, 0.24, 1, 1.21);
  private readonly viewport = new Vector2(1, 1);
  private readonly frame: IUniform<Texture | null> = { value: null };
  private section: Place = { left: 0, top: 0, width: 1, height: 1 };
  private height = 1;
  private view: Readonly<View> | null = null;
  private dirty = true;
  private progress = 0;
  /** Where the pointer was last frame, for how far it's moved since. */
  private readonly pointed = new Vector2();

  /**
   * The film for `host`'s stage, made on the scenery's GPU (`pause` between its steps) and shown by it from then on,
   * its camera nudged a little as `pointer` moves.
   */
  static async create(
    scenery: Scenery,
    host: HTMLElement,
    options: FilmOptions,
    pointer: Pointer,
    pause: () => Promise<void>,
  ): Promise<Projection> {
    const film = await Film.create(scenery.renderer, options, pause);
    return new Projection(scenery, host, options, pointer, pause, film);
  }

  private constructor(
    scenery: Scenery,
    private readonly host: HTMLElement,
    private readonly options: FilmOptions,
    private readonly pointer: Pointer,
    private readonly pause: () => Promise<void>,
    film: Film,
  ) {
    this.film = film;
    this.stage = one(host, "[data-flight-stage]");
    this.box = one(host, "[data-flight-film]");
    this.object = new Mesh(
      new PlaneGeometry(2, 2),
      new ShaderMaterial({
        vertexShader: vertex,
        fragmentShader: fragment,
        uniforms: {
          uRect: { value: this.rect },
          uStage: { value: this.stageAt },
          uFade: { value: this.fade },
          uViewport: { value: this.viewport },
          uFrame: this.frame,
        },
        transparent: true,
        premultipliedAlpha: true,
        depthTest: false,
        depthWrite: false,
      }),
    );
    this.object.frustumCulled = false;
    this.object.renderOrder = 1;
    this.object.visible = false;
    const measure = () => {
      this.section = scenery.locate(host);
      this.height = Math.max(this.stage.offsetHeight, 1);
      const style = getComputedStyle(this.box);
      const stop = (name: string) => parseFloat(style.getPropertyValue(name)) / 100;
      this.fade.set(stop("--fade-clear"), stop("--fade-solid"), stop("--fade-foot"), stop("--fade-end"));
      this.size();
    };
    measure();
    onResize(host, measure);
    ScrollTrigger.addEventListener("refresh", measure);
    scenery.add(this);
    this.show(film);
  }

  /** How far in the camera should be, 0 (the opening shot) to 1 (at rest). */
  play(progress: number): void {
    this.progress = progress;
    this.film?.play(progress);
  }

  /** Puts the camera there at once. */
  jump(progress: number): void {
    this.progress = progress;
    this.film?.jump(progress);
  }

  update(view: Readonly<View>, seconds: number): boolean {
    const { film } = this;
    const top = this.section.top - view.scroll;
    // Where the sticky stage is: with the section until its top reaches the window's, then held there until the
    // section's foot comes up to its own.
    const y = Math.min(Math.max(top, 0), top + this.section.height - this.height);
    const was = this.object.visible;
    if (film == null || y >= view.height || y + this.height * this.fade.w <= 0) {
      this.object.visible = false;
      return was;
    }
    this.object.visible = true;
    const moved = y !== this.stageAt.x;
    this.stageAt.set(y, this.height);
    this.rect.set(this.section.left, 0, this.section.width, this.height);
    // A mouse's movement, not a finger's: a finger moves only to scroll, and the camera would lurch each time.
    const { pointer, pointed } = this;
    if (pointer.mouse && was) film.nudge.push(pointer.x - pointed.x, pointer.y - pointed.y);
    pointed.set(pointer.x, pointer.y);
    if (film.update(seconds)) this.dirty = true;
    return this.dirty || moved || !was;
  }

  render(): void {
    if (this.dirty && this.film != null) this.show(this.film);
  }

  resize(view: Readonly<View>): void {
    this.view = view;
    this.viewport.set(view.width, view.height);
    this.size();
  }

  restore(renderer: WebGLRenderer): void {
    this.film = null;
    this.object.visible = false;
    this.host.classList.remove("is-playing");
    void Film.create(renderer, this.options, this.pause).then((film) => {
      film.jump(this.progress);
      this.film = film;
      this.size();
      this.show(film);
    });
  }

  /** Draws the film's frame, for the scenery to show, and marks the section playing. */
  private show(film: Film): void {
    this.dirty = false;
    film.draw();
    this.frame.value = film.texture;
    this.host.classList.add("is-playing");
  }

  /** Sizes the film's frames to the stage, as sharp as the scenery draws, within the film's budget. */
  private size(): void {
    const { view, film, height } = this;
    if (view == null || film == null) return;
    const { width } = this.section;
    const budget = this.options.light ? BUDGET.light : BUDGET.full;
    const ratio = Math.min(view.ratio, 2, Math.sqrt(budget / (width * height)));
    film.setSize(Math.max(1, Math.round(width * ratio)), Math.max(1, Math.round(height * ratio)));
    this.dirty = true;
  }
}
