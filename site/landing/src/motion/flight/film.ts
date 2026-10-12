import {
  DepthTexture,
  LessEqualCompare,
  LinearFilter,
  LinearMipmapLinearFilter,
  Matrix4,
  MeshBasicMaterial,
  OrthographicCamera,
  PerspectiveCamera,
  Scene,
  type Texture,
  UnsignedIntType,
  Vector3,
  type WebGLRenderer,
  WebGLRenderTarget,
} from "three";
import { Airliner, layout } from "./airliner";
import { Cabin } from "./cabin";
import { bakeClouds, CloudVolume } from "./clouds";
import { Focus } from "./focus";
import { Model } from "./model";
import { SUN, shared } from "./shading";
import { type Frame, Shots } from "./shots";

/**
 * The flight, as a film: three.js drawing the airliner over the cloud sea and the cabin inside it, on the scenery's
 * GPU, into a frame of its own that the scenery shows where the page has the flight (motion/scenery/projection.ts).
 * The page's scroll plays it (motion/flight.ts sets how far in it should be): the camera flies in through a window to
 * the laptop inside (motion/flight/shots.ts) as far as the scroll has come, and back out again scrolling up. It
 * follows the scroll on a spring, so however the page is scrolled (a wheel's notches, a flick), the camera's speed
 * never jumps. The world runs on its own clock meanwhile: the clouds drift past and the strobes flash, scrolled or not.
 *
 * Everything is made ready when the film is first needed (the models loaded, made in Blender: model.ts), a step at a
 * time so the page keeps scrolling smoothly meanwhile, and put on the GPU before it's shown, so nothing stalls
 * mid-flight. Shadows are worked out once,
 * as nothing that casts them moves. It draws only what can be seen: the cloud deck isn't marched while the fuselage
 * fills the frame, nor from inside, where only the sky shows through the windows; and inside, once the camera has
 * caught up with the scroll, a frame is drawn again only when the laptop's screen changes, as its cards come and go.
 */

/** How fast the air goes past the plane, in metres a second: slow motion, for calm. */
const SPEED = 23;
/** How briskly the camera follows the scroll: a critically damped spring's natural frequency, in radians a second. */
const FOLLOW = 7;
/** The frame's corners, in normalised device coordinates. */
const CORNERS = [
  [-1, -1],
  [1, -1],
  [-1, 1],
  [1, 1],
] as const;

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
  /** Fewer steps through the clouds and a lighter lens, for small screens and slower devices. */
  readonly light: boolean;
}

export class Film {
  /** The frame as last drawn. */
  texture: Texture | null = null;
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
  /** Seconds the world has run (the clouds drifting, the strobes). */
  private clock = 0;
  /** Seconds the laptop has been studying: only while the camera's near enough to see it. */
  private studied = 0;
  /** Whether the frame needs drawing whatever else happens. */
  private stale = true;
  /** How far in the camera is (0 the opening shot, 1 at rest), how fast it's going there, and how far in the scroll says. */
  private progress = 0;
  private velocity = 0;
  private target = 0;

  /**
   * Makes the film on `renderer` a step at a time, `pause` between the steps (until a frame the page is still), so
   * the page keeps moving smoothly meanwhile.
   */
  static async create(renderer: WebGLRenderer, options: FilmOptions, pause: () => Promise<void>): Promise<Film> {
    const loading = Model.load();
    const maps = await bakeClouds(renderer, pause);
    const model = await loading;
    await pause();
    const exterior = shadowMap(2048, sunView(new Vector3(0, 0.5, 0), 34, 70), 2.6);
    const airliner = new Airliner(exterior.uniforms, maps, model.airliner);
    await pause();
    const interior = shadowMap(2048, sunView(Cabin.light.centre, Cabin.light.reach, Cabin.light.depth), 3.5);
    // The laptop's screen is drawn in the page's type.
    await Promise.all([document.fonts.load("500 64px Geist"), document.fonts.load("300 64px Juana")]);
    const cabin = new Cabin(interior.uniforms, renderer.capabilities.getMaxAnisotropy(), model);
    await pause();
    const film = new Film(renderer, options, { airliner, cabin, maps, exterior, interior });
    await pause();
    await film.prepare(pause);
    return film;
  }

  private constructor(
    private readonly renderer: WebGLRenderer,
    options: FilmOptions,
    made: {
      readonly airliner: Airliner;
      readonly cabin: Cabin;
      readonly maps: Awaited<ReturnType<typeof bakeClouds>>;
      readonly exterior: ReturnType<typeof shadowMap>;
      readonly interior: ReturnType<typeof shadowMap>;
    },
  ) {
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
      },
    };
  }

  /**
   * Draws the shadows, compiles every shader and sends everything to the GPU before the first frame, the cabin and
   * the lens included, so nothing stalls the first time it's seen, mid-flight.
   */
  private async prepare(pause: () => Promise<void>): Promise<void> {
    this.casters.draw();
    await pause();
    this.shots.at(1, 0, this.frame);
    this.pose();
    this.cabin.group.visible = true;
    await this.renderer.compileAsync(this.scene, this.camera);
    await pause();
    // A frame of the rest, drawn before the film is shown, to put the cabin, the laptop's screen and the lens in place.
    this.clouds.render(this.renderer, this.camera, true);
    this.focus.render(this.renderer, this.scene, 1, this.cabin.screen.centre);
    await pause();
  }

  /** How far in the camera should be, 0 (the opening shot) to 1 (at rest): it gets there on its spring. */
  play(progress: number): void {
    this.target = progress;
  }

  /** Puts the camera where the scroll says at once, as when the film is first shown, mid-scroll. */
  jump(progress: number): void {
    this.target = progress;
    this.progress = progress;
    this.velocity = 0;
    this.stale = true;
  }

  /** Draws its frames `width` × `height` pixels. */
  setSize(width: number, height: number): void {
    if (width === this.width && height === this.height) return;
    this.width = width;
    this.height = height;
    this.clouds.setSize(width, height);
    this.focus.setSize(width, height);
    this.stale = true;
  }

  /**
   * Runs the film on by `seconds`, the world and the camera: whether its frame has changed. Inside, with the camera
   * caught up with the scroll, only the laptop's screen moves, and the frame changes only when it does.
   */
  update(seconds: number): boolean {
    this.clock += seconds;
    this.follow(seconds);
    if (this.frame.inside) this.studied += seconds;
    const studying = this.frame.inside && this.cabin.study.at(this.studied);
    return this.stale || this.progress !== this.target || this.frame.deck || studying;
  }

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

  /** Draws the film as it is now, into its frame. */
  draw(): void {
    this.stale = false;
    shared.uTime.value = this.clock;
    shared.uScroll.value = (this.clock * SPEED) % 100000;
    this.shots.at(this.progress, this.clock, this.frame);
    this.pose();
    this.airliner.clear = this.frame.clear;
    this.cabin.group.visible = this.frame.inside;
    this.clouds.render(this.renderer, this.camera, this.frame.deck && !this.covered());
    this.texture =
      this.frame.focus > 0.001
        ? this.focus.render(this.renderer, this.scene, this.frame.focus, this.cabin.screen.centre)
        : this.focus.plain(this.renderer, this.scene);
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
}
