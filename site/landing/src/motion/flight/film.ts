import { gsap } from "gsap";
import {
  Color,
  DepthTexture,
  LessEqualCompare,
  LinearFilter,
  LinearMipmapLinearFilter,
  Matrix4,
  MeshBasicMaterial,
  OrthographicCamera,
  PerspectiveCamera,
  Scene,
  UnsignedIntType,
  Vector3,
  WebGLRenderer,
  WebGLRenderTarget,
} from "three";
import { onResize } from "../dom";
import { Airliner, layout } from "./airliner";
import { Cabin } from "./cabin";
import { bakeClouds, CloudVolume } from "./clouds";
import { Focus } from "./focus";
import { SUN, shared } from "./shading";
import { type Frame, Shots } from "./shots";

/**
 * The flight, as a film: three.js drawing the airliner over the cloud sea and the cabin inside it. The page's scroll
 * plays it (motion/flight.ts sets how far in it should be): the camera flies in through a window to the laptop
 * inside (motion/flight/shots.ts) as far as the scroll has come, and back out again scrolling up. It follows the
 * scroll on a spring, so however the page is scrolled (a wheel's notches, a flick), the camera's speed never jumps.
 * The world runs on its own clock meanwhile: the clouds drift past and the strobes flash, scrolled or not.
 *
 * Everything is made on the device when the film is first needed, a step at a time so the page keeps scrolling
 * smoothly meanwhile, and put on the GPU before it's shown, so nothing stalls mid-flight. Shadows are worked out once,
 * as nothing that casts them moves. It draws only what can be seen: the cloud deck isn't marched while the fuselage
 * fills the frame, nor from inside, where only the sky shows through the windows; and inside, once the camera has
 * caught up with the scroll, the frame is still, so it's drawn once and left. On its opening shot, it watches its own
 * pace: when frames come too slowly it draws fewer pixels, and more again once there's room (mid-flight, a change
 * would show).
 */

/** How fast the air goes past the plane, in metres a second: slow motion, for calm. */
const SPEED = 23;
/** How briskly the camera follows the scroll: a critically damped spring's natural frequency, in radians a second. */
const FOLLOW = 7;
/** The most pixels the film draws, and the sharpest it draws them. */
const BUDGET = { pixels: 2.4e6, ratio: 2 } as const;
const LIGHT_BUDGET = { pixels: 1.3e6, ratio: 2 } as const;

/** The frame's corners, in normalised device coordinates. */
const CORNERS = [
  [-1, -1],
  [1, -1],
  [-1, 1],
  [1, 1],
] as const;

/** Waits a frame, so building doesn't hold the page up. */
const nextFrame = () => new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));

/** A sun's-eye view for shadows: an orthographic camera looking along the sunlight at `centre`, `reach` around. */
function sunView(centre: Vector3, reach: number, depth: number): OrthographicCamera {
  const camera = new OrthographicCamera(-reach, reach, reach, -reach, 0.05, depth * 2);
  camera.position.copy(centre).addScaledVector(SUN, depth);
  camera.up.set(0, 1, 0);
  camera.lookAt(centre);
  camera.updateMatrixWorld();
  camera.updateProjectionMatrix();
  return camera;
}

/** A depth map from a sun's-eye view, filtered by the hardware for soft edges, and the uniforms that read it. */
function shadowMap(size: number, view: OrthographicCamera, softness: number) {
  const target = new WebGLRenderTarget(size, size, { depthBuffer: true });
  const depth = new DepthTexture(size, size, UnsignedIntType);
  depth.compareFunction = LessEqualCompare;
  depth.minFilter = LinearFilter;
  depth.magFilter = LinearFilter;
  target.depthTexture = depth;
  const matrix = new Matrix4().multiplyMatrices(view.projectionMatrix, view.matrixWorldInverse);
  return {
    target,
    view,
    uniforms: {
      uShadow: { value: depth },
      uShadowMatrix: { value: matrix },
      uShadowTexel: { value: softness / size },
    },
  };
}

export interface FilmOptions {
  /** Fewer steps through the clouds and fewer pixels, for small screens. */
  readonly light: boolean;
}

export class Film {
  readonly canvas: HTMLCanvasElement;
  private readonly renderer: WebGLRenderer;
  private readonly scene = new Scene();
  private readonly camera = new PerspectiveCamera(36, 16 / 9, 0.03, 9000);
  private readonly shots: Shots;
  private readonly airliner: Airliner;
  private readonly cabin: Cabin;
  private readonly clouds: CloudVolume;
  private readonly focus: Focus;
  private readonly frame: Frame = {
    position: new Vector3(),
    aim: new Vector3(),
    roll: 0,
    fov: 36,
    clear: 0,
    inside: false,
    focus: 0,
    deck: true,
  };
  private readonly casters: { readonly draw: () => void };
  private readonly ray = new Vector3();
  private width = 1;
  private height = 1;
  /** Pixels drawn per CSS pixel: 0 until the first resize sets it. */
  private ratio = 0;
  private ceiling = 1;
  /** Seconds the world has run (the clouds drifting, the strobes). */
  private clock = 0;
  /** How far in the camera is (0 the opening shot, 1 at rest), how fast it's going there, and how far in the scroll says. */
  private progress = 0;
  private velocity = 0;
  private target = 0;
  private ticking = false;
  private showing = false;
  private slow = 0;
  private quick = 0;
  private shown = false;
  /** Called once the film has drawn its first frame. */
  onShown: (() => void) | null = null;

  /**
   * Makes the film for `host`, a step at a time, a frame apart, so the page keeps scrolling smoothly while it's made;
   * null where WebGL 2 isn't there or fails, which leaves whatever stands in for it (the poster) where it is.
   */
  static async create(host: HTMLElement, options: FilmOptions): Promise<Film | null> {
    let renderer: WebGLRenderer;
    try {
      renderer = new WebGLRenderer({ antialias: true, powerPreference: "high-performance", stencil: false });
    } catch {
      return null;
    }
    const maps = await bakeClouds(renderer, nextFrame);
    await nextFrame();
    const exterior = shadowMap(2048, sunView(new Vector3(0, 0.5, 0), 34, 70), 2.6);
    const airliner = new Airliner(exterior.uniforms, maps);
    await nextFrame();
    const interior = shadowMap(2048, sunView(Cabin.light.centre, Cabin.light.reach, Cabin.light.depth), 3.5);
    const cabin = new Cabin(interior.uniforms, renderer.capabilities.getMaxAnisotropy());
    await nextFrame();
    const film = new Film(host, renderer, options, { airliner, cabin, maps, exterior, interior });
    await nextFrame();
    await film.prepare();
    return film;
  }

  private constructor(
    private readonly host: HTMLElement,
    renderer: WebGLRenderer,
    private readonly options: FilmOptions,
    made: {
      readonly airliner: Airliner;
      readonly cabin: Cabin;
      readonly maps: Awaited<ReturnType<typeof bakeClouds>>;
      readonly exterior: ReturnType<typeof shadowMap>;
      readonly interior: ReturnType<typeof shadowMap>;
    },
  ) {
    this.renderer = renderer;
    this.canvas = renderer.domElement;
    renderer.setClearColor(new Color(1, 1, 1), 1);

    const { exterior, interior } = made;
    // The plane's outline as the sun sees it, which the clouds take their shade from.
    const occupancy = new WebGLRenderTarget(1024, 1024, {
      generateMipmaps: true,
      minFilter: LinearMipmapLinearFilter,
      magFilter: LinearFilter,
      samples: 4,
    });
    const occupancyMatrix = new Matrix4().multiplyMatrices(
      exterior.view.projectionMatrix,
      exterior.view.matrixWorldInverse,
    );

    this.airliner = made.airliner;
    this.cabin = made.cabin;
    this.clouds = new CloudVolume(
      made.maps,
      { uOccupancy: { value: occupancy.texture }, uOccupancyMatrix: { value: occupancyMatrix } },
      this.camera,
      options.light ? 56 : 96,
    );
    this.focus = new Focus(this.camera, options.light);
    this.scene.add(this.airliner.group, this.cabin.group, this.clouds.backdrop);
    this.shots = new Shots(this.cabin.screen);

    // What casts shadows, drawn once: the plane's depth from the sun and its outline for the clouds; and inside, the
    // cabin's, the fuselage's windows letting the sun through.
    const black = new MeshBasicMaterial({ color: 0x000000 });
    const outside = new Scene();
    outside.add(this.airliner.casters);
    const inside = new Scene();
    inside.add(this.airliner.walls, this.cabin.casters);
    this.casters = {
      draw: () => {
        const { renderer: r } = this;
        r.setRenderTarget(exterior.target);
        r.clear();
        r.render(outside, exterior.view);
        r.setRenderTarget(interior.target);
        r.clear();
        r.render(inside, interior.view);
        outside.overrideMaterial = black;
        r.setRenderTarget(occupancy);
        r.clear();
        r.render(outside, exterior.view);
        outside.overrideMaterial = null;
        r.setRenderTarget(null);
      },
    };

    this.canvas.addEventListener("webglcontextlost", (event) => {
      event.preventDefault();
    });
    this.canvas.addEventListener("webglcontextrestored", () => {
      this.casters.draw();
      this.wake();
    });
    host.append(this.canvas);
    this.resize();
    onResize(host, () => {
      this.resize();
    });
  }

  /**
   * Draws the shadows, compiles every shader and sends everything to the GPU before the first frame, the cabin and
   * the lens included, so nothing stalls the first time it's seen, mid-flight.
   */
  private async prepare(): Promise<void> {
    this.casters.draw();
    await nextFrame();
    this.shots.at(1, 0, this.frame);
    this.pose();
    this.cabin.group.visible = true;
    await this.renderer.compileAsync(this.scene, this.camera);
    await nextFrame();
    // A frame of the rest, drawn before the film is shown, to put the cabin, the laptop's screen and the lens in place.
    this.clouds.render(this.renderer, this.camera, true);
    this.focus.render(this.renderer, this.scene, 1, this.cabin.screen.centre);
    await nextFrame();
  }

  /** The film is on screen: it draws as it goes (or, at rest, its still frame once). */
  show(): void {
    this.showing = true;
    this.wake();
  }

  /** The film is off screen: it stops drawing. */
  hide(): void {
    this.showing = false;
    this.sleep();
  }

  /** How far in the camera should be, 0 (the opening shot) to 1 (at rest): it gets there on its spring. */
  play(progress: number): void {
    if (progress === this.target) return;
    this.target = progress;
    this.wake();
  }

  /** Puts the camera where the scroll says at once, as when the film is first shown, mid-scroll. */
  jump(progress: number): void {
    this.target = progress;
    this.progress = progress;
    this.velocity = 0;
    this.wake();
  }

  private wake(): void {
    if (!this.showing || this.ticking) return;
    this.ticking = true;
    gsap.ticker.add(this.tick);
  }

  private sleep(): void {
    if (!this.ticking) return;
    this.ticking = false;
    gsap.ticker.remove(this.tick);
  }

  private readonly tick = (_time: number, deltaMs: number): void => {
    const seconds = Math.min(deltaMs, 50) / 1000;
    this.clock += seconds;
    this.follow(seconds);
    this.draw();
    const settled = this.progress === this.target;
    // Inside, with the camera caught up, nothing moves: drawn once, then nothing until the scroll moves.
    if (settled && !this.frame.deck) this.sleep();
    // A change of resolution mid-flight would show, so it's left for the opening.
    if (settled && this.progress === 0) this.pace(deltaMs);
  };

  /**
   * Moves the camera toward where the scroll says on a critically damped spring, in small steps for steadiness:
   * its speed changes only gradually, and it arrives without overshooting. Close enough, it's simply there.
   */
  private follow(seconds: number): void {
    const steps = Math.ceil(seconds / 0.004);
    const step = seconds / steps;
    for (let index = 0; index < steps; index++) {
      const pull = FOLLOW * FOLLOW * (this.target - this.progress) - 2 * FOLLOW * this.velocity;
      this.velocity += pull * step;
      this.progress += this.velocity * step;
    }
    if (Math.abs(this.target - this.progress) < 1e-5 && Math.abs(this.velocity) < 1e-4) {
      this.progress = this.target;
      this.velocity = 0;
    }
  }

  /** Draws the film as it is now. */
  draw(): void {
    shared.uTime.value = this.clock;
    shared.uScroll.value = (this.clock * SPEED) % 100000;
    this.shots.at(this.progress, this.clock, this.frame);
    this.pose();
    this.airliner.clear = this.frame.clear;
    this.cabin.group.visible = this.frame.inside;
    this.clouds.render(this.renderer, this.camera, this.frame.deck && !this.covered());
    if (this.frame.focus > 0.001) {
      this.focus.render(this.renderer, this.scene, this.frame.focus, this.cabin.screen.centre);
    } else {
      this.renderer.setRenderTarget(null);
      this.renderer.render(this.scene, this.camera);
    }
    if (!this.shown) {
      this.shown = true;
      this.onShown?.();
    }
  }

  /**
   * Whether the fuselage fills the frame, so nothing beyond it shows: from outside, every corner's ray meets the round
   * barrel of it (the rays that meet a long cylinder fill a convex wedge of the frame, so the corners are enough).
   */
  private covered(): boolean {
    const { camera, ray } = this;
    const origin = camera.position;
    const radius = layout.radius;
    for (const [x, y] of CORNERS) {
      ray.set(x, y, 1).unproject(camera).sub(origin).normalize();
      const a = ray.x * ray.x + ray.y * ray.y;
      const b = 2 * (origin.x * ray.x + origin.y * ray.y);
      const c = origin.x * origin.x + origin.y * origin.y - radius * radius;
      const disc = b * b - 4 * a * c;
      if (c <= 0 || a < 1e-9 || disc < 0) return false;
      const t = (-b - Math.sqrt(disc)) / (2 * a);
      const z = origin.z + ray.z * t;
      if (t <= 0 || z < layout.barrel.front || z > layout.barrel.back) return false;
    }
    return true;
  }

  /**
   * Points the camera as the shot says. A frame narrower than 16:9 keeps its width, down to nearly square; a
   * portrait one is that frame, cropped at the sides.
   */
  private pose(): void {
    const { camera, frame } = this;
    camera.position.copy(frame.position);
    camera.up.set(0, 1, 0);
    camera.lookAt(frame.aim);
    camera.rotateZ(frame.roll);
    const aspect = this.width / this.height;
    const tangent = Math.tan((frame.fov * Math.PI) / 360);
    const kept = Math.max(aspect, 1.1);
    const vertical = kept < 16 / 9 ? Math.atan((tangent * 16) / 9 / kept) * 2 : (frame.fov * Math.PI) / 180;
    camera.fov = (vertical * 180) / Math.PI;
    camera.aspect = aspect;
    camera.updateProjectionMatrix();
    camera.updateMatrixWorld();
  }

  private resize(): void {
    this.width = Math.max(this.host.clientWidth, 1);
    this.height = Math.max(this.host.clientHeight, 1);
    const budget = this.options.light ? LIGHT_BUDGET : BUDGET;
    this.ceiling = Math.min(
      window.devicePixelRatio,
      budget.ratio,
      Math.sqrt(budget.pixels / (this.width * this.height)),
    );
    this.ratio = this.ratio === 0 ? this.ceiling : Math.min(this.ratio, this.ceiling);
    this.apply();
  }

  private apply(): void {
    this.renderer.setPixelRatio(this.ratio);
    this.renderer.setSize(this.width, this.height, false);
    const width = Math.round(this.width * this.ratio);
    const height = Math.round(this.height * this.ratio);
    this.clouds.setSize(width, height);
    this.focus.setSize(width, height);
    // A still frame is drawn again at its new size.
    if (this.shown) this.wake();
  }

  /**
   * Keeps to the screen's pace while moving: two seconds of slow frames (below about 45 a second) and it draws
   * fewer pixels; five seconds of quick ones, and it draws more again, up to its ceiling.
   */
  private pace(deltaMs: number): void {
    if (deltaMs > 22) {
      this.slow += deltaMs;
      this.quick = 0;
    } else {
      this.quick += deltaMs;
      this.slow = Math.max(0, this.slow - deltaMs * 0.5);
    }
    if (this.slow > 2000 && this.ratio > 0.6) {
      this.ratio = Math.max(0.6, this.ratio * 0.85);
      this.slow = 0;
      this.apply();
    } else if (this.quick > 5000 && this.ratio < this.ceiling) {
      this.ratio = Math.min(this.ceiling, this.ratio * 1.1);
      this.quick = 0;
      this.apply();
    }
  }
}
