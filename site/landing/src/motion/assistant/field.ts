import {
  Mesh,
  NoBlending,
  OrthographicCamera,
  PlaneGeometry,
  Scene,
  ShaderMaterial,
  Vector2,
  WebGLRenderer,
} from "three";
import { onResize } from "../dom";
import { whileOnScreen } from "../loop";
import { damp } from "../math";

/**
 * The sea (motion/assistant/tuning.ts tunes it in development): how long its longest waves are (their frequency, per
 * unit of sea), how high, and how peaked their round crests.
 */
export const sea = { scale: 1.75, height: 0.32, sharpness: 1.3 };

/**
 * How stirred the sea is, which makes it both higher and quicker: idle, while words are typed, once they're sent, and
 * once the shockwave has passed.
 */
const IDLE = 0;
const TYPING = 0.45;
const SENT = 1;
const SETTLED = 0.15;

/**
 * The shockwave, a tsunami's shape crossing the sea (motion/assistant/tuning.ts tunes it in development), its sizes
 * in windows: how high its crest stands, in units of sea, and how wide; how far the sea draws down ahead of it, and
 * how far ahead it reaches; how much flatter the waves are there; and how much higher the sea swells beyond that, as
 * it nears.
 */
export const wave = { height: 0.09, width: 1.343, dip: 0.707, ahead: 2.4, reach: 1.97, flattened: 6, swell: 1.13 };

const vertex = /* glsl */ `
  varying vec2 vUv;

  void main() {
    vUv = position.xy * 0.5 + 0.5;
    gl_Position = vec4(position.xy, 0.0, 1.0);
  }
`;

/**
 * A wireframe sea seen from a little above it through the places in the row: where each ray from the eye meets it,
 * its grid is drawn, finer and fainter into the distance. There's one eye, before the row's middle, and each place
 * shows what it sees through where the place is on screen (uAt), so the row is a line of windows on one
 * sea, its horizon running straight through them and its waves on from one to the next.
 *
 * The sea is long, round swells with a gentler set over them, turned across them, the finer going slower, as on
 * deep water. Stirred (uStir), it goes higher and quicker, its waves keeping time by a clock that runs as fast as it's
 * stirred (uPhase); behind the shockwave the sea keeps the time of a calm one (uCalm).
 *
 * The shockwave crosses it with the light (uSweep, in places, as the row's --sweep): a crest across the sea, square
 * to the row. It's seen coming, its way readied (uSurge) once the words are sent: the sea swells beyond it, then
 * draws down and goes glassy, waves uFlattened times flatter, as it nears. Its crest's lines flare as it passes,
 * thicker and blooming, and behind it the sea settles, so each card is uncovered over still water.
 */
const fragment = /* glsl */ `
  uniform vec2 uSize;
  uniform float uAt;
  uniform float uMiddle;
  uniform float uSlid;
  uniform float uPlace;
  uniform float uGap;
  uniform float uPhase;
  uniform float uCalm;
  uniform float uStir;
  uniform float uSurge;
  uniform float uSweep;
  uniform float uScale;
  uniform float uHeight;
  uniform float uSharpness;
  uniform float uCrest;
  uniform float uWidth;
  uniform float uDip;
  uniform float uAhead;
  uniform float uReach;
  uniform float uFlattened;
  uniform float uSwell;
  varying vec2 vUv;

  // How much of the sea a window's height takes in, in radians; how far down the eye looks; how high it is; and how
  // far it looks.
  const float VIEW = 0.55;
  const float TILT = 0.2;
  const float EYE = 1.1;
  const float FAR = 40.0;
  // Each finer wave, turned and smaller.
  const mat2 FINER = mat2(1.6, 1.2, -1.2, 1.6);

  float aspect() {
    return uSize.x / uSize.y;
  }

  // How far across the row a pixel of this window is, in windows' widths from the row's middle, where the window is
  // on screen now (uAt), so the sea stays where it is as the windows go over it.
  float across() {
    return vUv.x - 0.5 + uAt;
  }

  // Where on the row p is seen, the same way: the one eye, in front of the row's middle, sees the sea all round it,
  // as many radians across a window as up it, so the windows show one sea, unstretched.
  float seen(vec3 p) {
    return atan(p.x, p.z) / (aspect() * VIEW);
  }

  // How far ahead of the shockwave's crest p is seen, in windows' widths: the crest is where the row's front is.
  float ahead(vec3 p) {
    return seen(p) - ((uSweep + uSlid - uMiddle) * uPlace - 0.5 - uGap * 0.5);
  }

  float hash(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
  }

  float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float bottom = mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x);
    float top = mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x);
    return mix(bottom, top, u.y) * 2.0 - 1.0;
  }

  // One swell's height at uv, from 0 in its troughs to 1 on its crests: round all over, its crests a little peaked
  // (sharpness), rising and falling along them, and wandering a little so it never repeats.
  float crest(vec2 uv, float sharpness) {
    uv += noise(uv * 0.5) * 0.4;
    float across = 0.5 + 0.5 * sin(uv.x);
    float along = 0.5 + 0.5 * sin(uv.y * 0.6 + 1.3);
    return pow(across * mix(0.55, 1.0, along), sharpness);
  }

  // The waves' height at q, at the time phase, from as many of them as asked: each finer one turned, smaller and
  // rounder, and, its uv grown, going slower over the sea.
  float waves(vec2 q, float phase, int count) {
    float frequency = uScale;
    float height = uHeight;
    float sharpness = uSharpness;
    float pace = phase * 0.8;
    vec2 uv = q * vec2(0.75, 1.0);
    float h = 0.0;
    for (int i = 0; i < 2; i++) {
      if (i >= count) break;
      h += (crest((uv + pace) * frequency, sharpness) + crest((uv - pace) * frequency, sharpness)) * height;
      uv = FINER * uv;
      frequency *= 1.7;
      height *= 0.18;
      sharpness = 1.0;
    }
    return h - uHeight * 0.7;
  }

  float sech2(float x) {
    float c = cosh(clamp(x, -12.0, 12.0));
    return 1.0 / (c * c);
  }

  // How far above the sea p is, the sea's waves from as many as asked.
  float above(vec3 p, int count) {
    float d = ahead(p);
    // Ahead of the crest, as it nears: swelling beyond, then drawn down and glassy up to the crest itself.
    float glassy = uSurge * (d > uAhead ? exp(-pow((d - uAhead) / uReach, 2.0)) : smoothstep(-uWidth, 0.0, d));
    float swelling = uSurge * exp(-pow((d - uAhead - 1.6 * uReach) / uReach, 2.0));
    float drawn = uSurge * uDip * exp(-pow((d - uAhead * 0.5) / (uAhead * 0.5), 2.0));
    // Behind it, settled, keeping a calm sea's time.
    float settled = 1.0 - smoothstep(-0.9, -0.1, d);
    float stir = mix(uStir, ${SETTLED.toFixed(2)}, settled);
    float energy = (0.55 + 0.75 * stir) * mix(1.0, uSwell, swelling) * mix(1.0, 1.0 / uFlattened, glassy);
    float h = waves(p.xz, mix(uPhase, uCalm, settled), count) * energy - drawn;
    // Its crest, as high as it is far, so it stands as high in every window, near and far.
    h += uCrest * exp(-pow(d / uWidth, 2.0)) * min(length(p.xz), 12.0);
    return p.y - h;
  }

  void main() {
    float around = across() * aspect() * VIEW;
    float up = (vUv.y - 0.5) * VIEW - TILT;
    vec3 from = vec3(0.0, EYE, 0.0);
    vec3 ray = vec3(sin(around) * cos(up), sin(up), cos(around) * cos(up));

    // Out along the ray, further each step, to the first step under the sea; nothing if it never goes under.
    float near = 0.0;
    float far = 0.3;
    float nearAbove = above(from, 2);
    float farAbove = above(from + ray * far, 2);
    for (int i = 0; i < 18; i++) {
      if (farAbove < 0.0 || far > FAR) break;
      near = far;
      nearAbove = farAbove;
      far = min(far * 1.32, FAR + 1.0);
      farAbove = above(from + ray * far, 2);
    }
    if (farAbove >= 0.0) {
      gl_FragColor = vec4(0.0);
      return;
    }
    // Then in between, to where it meets it.
    float t = far;
    for (int i = 0; i < 6; i++) {
      t = mix(near, far, nearAbove / (nearAbove - farAbove));
      float h = above(from + ray * t, 2);
      if (h < 0.0) {
        far = t;
        farAbove = h;
      } else {
        near = t;
        nearAbove = h;
      }
    }
    vec3 p = from + ray * t;

    // The flare: on the shockwave's crest.
    float flare = sech2(ahead(p) / (uWidth * 0.8));
    float hot = flare * flare;

    // The grid, its rows (across the view) drawn more than its columns; where it's finer than a pixel or two it fades
    // rather than shimmers. Round each line, a bloom, wider as it flares.
    vec2 cell = p.xz * vec2(2.2, 4.2);
    vec2 width = max(fwidth(cell), vec2(1e-4));
    vec2 apart = abs(fract(cell - 0.5) - 0.5) / width;
    vec2 fine = 1.0 - smoothstep(0.25, 0.8, width);
    vec2 line = (1.0 - min(apart / (1.5 + 1.0 * flare), 1.0)) * fine;
    vec2 bloom = exp(-apart / (2.0 + 10.0 * flare)) * fine;
    float columns = mix(0.12, 0.3, flare);
    float ink = max(line.y, line.x * columns);
    float glow = max(bloom.y, bloom.x * columns);

    float fade = exp(-t * 0.07) * smoothstep(FAR, 12.0, t);
    // As it flares, the lines burn white over a haze that their own light shows up against.
    vec3 dark = vec3(0.043, 0.043, 0.051);
    float haze = hot * 0.12 * fade;
    float lit = min(ink * mix(0.32, 1.0, flare) + glow * hot, 1.0) * fade;
    vec3 colour = mix(dark, vec3(1.0), hot) * lit + dark * haze * (1.0 - lit);
    gl_FragColor = vec4(colour, lit + haze * (1.0 - lit));
  }
`;

/** One place the sea shows through, and the canvas it's drawn into. */
interface View {
  readonly canvas: HTMLCanvasElement;
  readonly context: CanvasRenderingContext2D;
}

/**
 * The sea under the AI section's empty places (see `fragment`), drawn once a frame for each one by one renderer of
 * its own, off the page, and copied into the canvas in it: the places scroll with the page, and the scenery's picture
 * lags the page by a frame. It's drawn only while the section's on screen and the places are empty; the shockwave goes
 * where the row's --sweep is.
 */
export class Field {
  private readonly uniforms = {
    uSize: { value: new Vector2(1, 1) },
    uAt: { value: 0 },
    uMiddle: { value: 0 },
    uSlid: { value: 0 },
    uPlace: { value: 1 },
    uGap: { value: 0 },
    uPhase: { value: 0 },
    uCalm: { value: 0 },
    uStir: { value: 0 },
    uSurge: { value: 0 },
    uSweep: { value: -1 },
    uScale: { value: 1 },
    uHeight: { value: 0 },
    uSharpness: { value: 1 },
    uCrest: { value: 0 },
    uWidth: { value: 1 },
    uDip: { value: 0 },
    uAhead: { value: 1 },
    uReach: { value: 1 },
    uFlattened: { value: 1 },
    uSwell: { value: 1 },
  };
  private readonly scene = new Scene();
  private readonly camera = new OrthographicCamera();
  private readonly views: View[];
  /** The sea's time, which runs as fast as it's stirred, and a calm sea's, which the sea behind the shockwave keeps. */
  private phase = 0;
  private calm = 0;
  /** How stirred the sea is, and how readied the sea ahead of the shockwave, each now and where it's going. */
  private readonly stir = { now: IDLE, to: IDLE };
  private readonly surge = { now: 0, to: 0 };
  private covered = false;

  /** The sea in `canvases`, the places in `row`; null where WebGL isn't there. */
  static create(row: HTMLElement, canvases: HTMLCanvasElement[]): Field | null {
    try {
      const renderer = new WebGLRenderer({ alpha: true, antialias: false, depth: false, stencil: false });
      return new Field(row, renderer, canvases);
    } catch {
      return null;
    }
  }

  private constructor(
    private readonly row: HTMLElement,
    private readonly renderer: WebGLRenderer,
    canvases: HTMLCanvasElement[],
  ) {
    renderer.setClearColor(0x000000, 0);
    this.views = canvases.flatMap((canvas) => {
      const context = canvas.getContext("2d");
      return context == null ? [] : [{ canvas, context }];
    });
    const material = new ShaderMaterial({
      vertexShader: vertex,
      fragmentShader: fragment,
      // It's all there is in the frame, its alpha already in its colour, as the canvas takes it.
      blending: NoBlending,
      depthTest: false,
      depthWrite: false,
      uniforms: this.uniforms,
    });
    this.scene.add(new Mesh(new PlaneGeometry(2, 2), material));
    this.measure();
    onResize(row, () => {
      this.measure();
    });
    renderer.compile(this.scene, this.camera);
    this.draw();
    row.classList.add("is-fielded");
    whileOnScreen(row, this.tick);
  }

  /** Words are being typed, or are about to be: the sea stirs. */
  listen(): void {
    this.stir.to = TYPING;
  }

  /**
   * The words are sent: the sea heaves, quicker and higher, and readies for the shockwave, swelling and drawing down
   * ahead of it as it nears; behind it, the calm keeps time from now.
   */
  think(): void {
    this.stir.to = SENT;
    this.surge.to = 1;
    this.calm = this.phase;
  }

  /** The cards fill the places: nothing to draw under them, and the sea is as the shockwave left it, settled. */
  cover(): void {
    this.covered = true;
    this.phase = this.calm;
    this.stir.now = this.stir.to = SETTLED;
    this.surge.now = this.surge.to = 0;
  }

  /** The cards go, and the places show the sea again, already livening for what's asked next. */
  uncover(): void {
    this.covered = false;
    this.listen();
  }

  /** The row's been carried `places` along: the windows look onto the sea that far over. */
  slide(places: number): void {
    this.uniforms.uSlid.value = places;
  }

  /** Each canvas's size, and where its place is in the row as it's laid out now. */
  measure(): void {
    const ratio = Math.min(window.devicePixelRatio, 1.5);
    const shown = this.views.filter(({ canvas }) => canvas.offsetWidth > 0);
    const first = shown[0]?.canvas;
    if (first == null) return;
    const width = first.offsetWidth;
    const height = first.offsetHeight;
    const places = first.parentElement?.parentElement;
    const gap = places == null ? 0 : parseFloat(getComputedStyle(places).columnGap) || 0;
    let columns = 1;
    for (const { canvas } of shown) {
      canvas.width = Math.max(1, Math.round(width * ratio));
      canvas.height = Math.max(1, Math.round(height * ratio));
      columns = Math.max(columns, Math.round((canvas.parentElement?.offsetLeft ?? 0) / (width + gap)) + 1);
    }
    this.renderer.setSize(first.width, first.height, false);
    this.uniforms.uSize.value.set(first.width, first.height);
    this.uniforms.uPlace.value = (width + gap) / width;
    this.uniforms.uGap.value = gap / width;
    this.uniforms.uMiddle.value = (columns - 1) / 2;
    this.draw();
  }

  private readonly tick = (_time: number, deltaMs: number): void => {
    const seconds = Math.min(deltaMs, 50) / 1000;
    const { stir, surge } = this;
    stir.now = damp(stir.now, stir.to, 2.2, seconds);
    surge.now = damp(surge.now, surge.to, 3, seconds);
    this.phase += seconds * this.pace(stir.now);
    this.calm += seconds * this.pace(SETTLED);
    if (!this.covered) this.draw();
  };

  /** How quickly the sea's time runs, stirred `stir`. */
  private pace(stir: number): number {
    return 0.45 + 1.3 * stir;
  }

  /** How far across the row, in places, the shockwave's crest is. */
  private sweep(): number {
    const places = parseFloat(this.row.style.getPropertyValue("--sweep"));
    return Number.isNaN(places) ? -1 : places;
  }

  private draw(): void {
    const { uniforms } = this;
    uniforms.uPhase.value = this.phase;
    uniforms.uCalm.value = this.calm;
    uniforms.uStir.value = this.stir.now;
    uniforms.uSurge.value = this.surge.now;
    uniforms.uSweep.value = this.sweep();
    uniforms.uScale.value = sea.scale;
    uniforms.uHeight.value = sea.height;
    uniforms.uSharpness.value = sea.sharpness;
    uniforms.uCrest.value = wave.height;
    uniforms.uWidth.value = Math.max(wave.width, 0.01);
    uniforms.uDip.value = wave.dip;
    uniforms.uAhead.value = Math.max(wave.ahead, 0.01);
    uniforms.uReach.value = Math.max(wave.reach, 0.01);
    uniforms.uFlattened.value = wave.flattened;
    uniforms.uSwell.value = wave.swell;
    const source = this.renderer.domElement;
    const row = this.row.getBoundingClientRect();
    const middle = row.left + row.width / 2;
    for (const { canvas, context } of this.views) {
      const { left, right, width } = canvas.getBoundingClientRect();
      if (canvas.offsetWidth === 0 || right < 0 || left > window.innerWidth) continue;
      uniforms.uAt.value = (left + width / 2 - middle) / width;
      this.renderer.render(this.scene, this.camera);
      context.clearRect(0, 0, canvas.width, canvas.height);
      context.drawImage(source, 0, 0, canvas.width, canvas.height);
    }
  }
}
