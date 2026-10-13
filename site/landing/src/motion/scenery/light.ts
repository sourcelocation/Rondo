import { gsap } from "gsap";
import {
  LinearFilter,
  Mesh,
  OrthographicCamera,
  PlaneGeometry,
  Scene,
  ShaderMaterial,
  Vector2,
  Vector4,
  WebGLRenderTarget,
  type WebGLRenderer,
} from "three";
import { damp } from "../math";
import type { Mood } from "../frame";
import type { Pointer } from "../pointer";
import type { Picture, View } from "../scenery";

/** How many of the window's CSS pixels each of the light's covers, each way: it's all soft. */
const SCALE = 4;
/**
 * How the light gathers where cards come to rest, as a breath: each card asks it in `gather` more, to all of it at
 * most, and it lets the asking go slowly (`exhale`, per second); the light follows that more slowly still (`inhale`,
 * per second), so however the cards come it swells and settles smoothly, and with dozens at once it fills and holds.
 */
const gathering = { gather: 0.1, inhale: 0.9, exhale: 0.32 } as const;

const vertex = /* glsl */ `
  varying vec2 vUv;

  void main() {
    vUv = position.xy * 0.5 + 0.5;
    gl_Position = vec4(position.xy, 0.0, 1.0);
  }
`;

/** Four pastel lights, drifting on slow loops in a space that folds gently. */
const lights = /* glsl */ `
  uniform float uTime;
  uniform vec2 uResolution;
  uniform vec2 uLean;
  uniform float uStrength;
  uniform vec4 uWeights;
  uniform vec3 uSky;
  uniform vec3 uLilac;
  uniform vec3 uPeach;
  uniform vec3 uMint;
  varying vec2 vUv;

  // Simplex noise by Ian McEwan and Stefan Gustavson (Ashima Arts), MIT licence.
  vec3 permute(vec3 x) { return mod(((x * 34.0) + 1.0) * x, 289.0); }

  float snoise(vec2 v) {
    const vec4 C = vec4(0.211324865405187, 0.366025403784439, -0.577350269189626, 0.024390243902439);
    vec2 i = floor(v + dot(v, C.yy));
    vec2 x0 = v - i + dot(i, C.xx);
    vec2 i1 = (x0.x > x0.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
    vec4 x12 = x0.xyxy + C.xxzz;
    x12.xy -= i1;
    i = mod(i, 289.0);
    vec3 p = permute(permute(i.y + vec3(0.0, i1.y, 1.0)) + i.x + vec3(0.0, i1.x, 1.0));
    vec3 m = max(0.5 - vec3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), 0.0);
    m = m * m;
    m = m * m;
    vec3 x = 2.0 * fract(p * C.www) - 1.0;
    vec3 h = abs(x) - 0.5;
    vec3 ox = floor(x + 0.5);
    vec3 a0 = x - ox;
    m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
    vec3 g;
    g.x = a0.x * x0.x + h.x * x0.y;
    g.yz = a0.yz * x12.xz + h.yz * x12.yw;
    return 130.0 * dot(m, g);
  }

  float light(vec2 p, vec2 centre, float radius) {
    vec2 d = p - centre;
    return exp(-dot(d, d) / (radius * radius));
  }

  void main() {
    float aspect = uResolution.x / uResolution.y;
    vec2 p = (vUv - 0.5) * vec2(aspect, 1.0);
    float t = uTime * 0.05;
    vec2 q = p + 0.16 * vec2(snoise(p * 1.2 + vec2(t, -t)), snoise(p * 1.2 + vec2(4.7 - t, t)));
    vec2 lean = uLean * vec2(aspect, 1.0) * 0.1;
    vec2 c1 = vec2(-0.55 * aspect, 0.22) + 0.15 * vec2(sin(t * 1.3), cos(t * 1.1)) + lean;
    vec2 c2 = vec2(0.58 * aspect, 0.3) + 0.13 * vec2(cos(t * 0.9 + 1.0), sin(t * 1.4)) - lean * 0.6;
    vec2 c3 = vec2(0.42 * aspect, -0.36) + 0.16 * vec2(sin(t * 1.15 + 2.0), cos(t * 0.8)) + lean * 0.4;
    vec2 c4 = vec2(-0.4 * aspect, -0.38) + 0.14 * vec2(cos(t * 1.05 + 3.0), sin(t * 1.25)) - lean * 0.3;
    float size = 0.42 + 0.12 * aspect;

    vec3 colour = vec3(1.0);
    colour = mix(colour, uSky, light(q, c1, size) * uWeights.x * uStrength);
    colour = mix(colour, uLilac, light(q, c2, size * 0.95) * uWeights.y * uStrength);
    colour = mix(colour, uPeach, light(q, c3, size * 0.9) * uWeights.z * uStrength);
    colour = mix(colour, uMint, light(q, c4, size * 0.85) * uWeights.w * uStrength);
    gl_FragColor = vec4(colour, 1.0);
  }
`;

/**
 * The light, stretched over the window, and gathered (uGather: where, CSS pixels from the window's top left, how far
 * round it, and how much): there, softly, it's drawn in from further out, up to PULL of the way, and its colours
 * deepen, up to DEEPEN more, so an aura comes up round what it gathers to; where the light is white it stays white.
 * A trace of noise keeps such soft gradients from banding.
 */
const blit = /* glsl */ `
  uniform sampler2D uLight;
  uniform vec2 uResolution;
  uniform vec4 uGather;
  varying vec2 vUv;

  const float PULL = 0.22;
  const float DEEPEN = 0.6;

  void main() {
    vec2 at = vec2(vUv.x, 1.0 - vUv.y) * uResolution;
    vec2 away = at - uGather.xy;
    float held = uGather.w * exp(-dot(away, away) / (uGather.z * uGather.z));
    vec2 from = at + away * held * PULL;
    vec3 colour = texture(uLight, vec2(from.x, uResolution.y - from.y) / uResolution).rgb;
    colour = 1.0 - (1.0 - colour) * (1.0 + held * DEEPEN);
    float grain = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    float tint = 1.0 - min(colour.r, min(colour.g, colour.b));
    colour += (grain - 0.5) / 200.0 * smoothstep(0.0, 0.04, tint);
    gl_FragColor = vec4(colour, 1.0);
  }
`;

function rgb(hex: string): [number, number, number] {
  const value = Number.parseInt(hex.replace("#", ""), 16);
  return [((value >> 16) & 255) / 255, ((value >> 8) & 255) / 255, (value & 255) / 255];
}

/**
 * The light behind the page: four pastel lights that change with each section's mood, drawn at a quarter of the
 * window's resolution, and only when they change: as the mood turns, and where there's a mouse, as they drift and
 * lean toward it. Every frame of the scenery stretches that picture over the window, gathering it a little where
 * cards come to rest, as they do on the collection's deck.
 */
export class Light implements Picture {
  readonly object: Mesh;
  private readonly target = new WebGLRenderTarget(1, 1, { depthBuffer: false, generateMipmaps: false });
  private readonly pass = new Scene();
  private readonly camera = new OrthographicCamera();
  private readonly uniforms = {
    uTime: { value: 40 },
    uResolution: { value: new Vector2(1, 1) },
    uLean: { value: new Vector2() },
    uStrength: { value: 0 },
    uWeights: { value: [1, 1, 1, 1] },
  };
  private readonly state = { strength: 0, w0: 1, w1: 1, w2: 1, w3: 1 };
  private readonly lean = { x: 0, y: 0 };
  /**
   * Where the light gathers, on the page (CSS pixels), how far round it, how much it's asked to, and how much it has,
   * from 0 to 1.
   */
  private readonly gathered = { x: 0, y: 0, reach: 1, asked: 0, held: 0 };
  private readonly gather = new Vector4();
  /** How far the page is scrolled, as the light last saw it. */
  private scroll = 0;
  private dirty = true;

  /** Where there's a mouse (`drifting`), the lights drift and lean toward it. */
  constructor(
    private readonly drifting: boolean,
    private readonly pointer: Pointer,
  ) {
    this.target.texture.minFilter = LinearFilter;
    this.target.texture.magFilter = LinearFilter;
    const styles = getComputedStyle(document.documentElement);
    const colour = (name: string) => ({ value: rgb(styles.getPropertyValue(name).trim()) });
    const quad = new Mesh(
      new PlaneGeometry(2, 2),
      new ShaderMaterial({
        vertexShader: vertex,
        fragmentShader: lights,
        uniforms: {
          ...this.uniforms,
          uSky: colour("--sky"),
          uLilac: colour("--lilac"),
          uPeach: colour("--peach"),
          uMint: colour("--mint"),
        },
        depthTest: false,
        depthWrite: false,
      }),
    );
    quad.frustumCulled = false;
    this.pass.add(quad);
    this.object = new Mesh(
      new PlaneGeometry(2, 2),
      new ShaderMaterial({
        vertexShader: vertex,
        fragmentShader: blit,
        uniforms: {
          uLight: { value: this.target.texture },
          uResolution: this.uniforms.uResolution,
          uGather: { value: this.gather },
        },
        depthTest: false,
        depthWrite: false,
      }),
    );
    this.object.frustumCulled = false;
    this.object.renderOrder = 0;
  }

  /** Turns the light toward `mood`, over `duration` seconds. */
  mood({ strength, weights }: Mood, duration = 2): void {
    gsap.to(this.state, {
      strength,
      w0: weights[0],
      w1: weights[1],
      w2: weights[2],
      w3: weights[3],
      duration,
      ease: "sine.inOut",
      overwrite: "auto",
      onUpdate: () => {
        this.dirty = true;
      },
    });
  }

  /** A card has come to rest at (`x`, `y`) on the window: the light gathers round it, `reach` round, a little more. */
  settle(x: number, y: number, reach: number): void {
    Object.assign(this.gathered, {
      x,
      y: y + this.scroll,
      reach,
      asked: Math.min(1, this.gathered.asked + gathering.gather),
    });
  }

  resize(view: Readonly<View>): void {
    this.target.setSize(Math.ceil(view.width / SCALE), Math.ceil(view.height / SCALE));
    this.uniforms.uResolution.value.set(view.width, view.height);
    this.dirty = true;
  }

  update(view: Readonly<View>, seconds: number): boolean {
    if (this.drifting && this.state.strength > 0.002) {
      this.uniforms.uTime.value += seconds;
      this.lean.x = damp(this.lean.x, this.pointer.x, 1.2, seconds);
      this.lean.y = damp(this.lean.y, -this.pointer.y, 1.2, seconds);
      this.dirty = true;
    }
    this.scroll = view.scroll;
    return this.breathe(seconds) || this.dirty;
  }

  /** Moves the light's gathering on by `seconds`; whether it's still gathered at all, to be drawn again. */
  private breathe(seconds: number): boolean {
    const { gathered } = this;
    const was = this.gather.w > 0;
    gathered.asked = damp(gathered.asked, 0, gathering.exhale, seconds);
    gathered.held = damp(gathered.held, gathered.asked, gathering.inhale, seconds);
    const held = gathered.held > 0.002 ? gathered.held : 0;
    this.gather.set(gathered.x, gathered.y - this.scroll, gathered.reach, held);
    return was || held > 0;
  }

  render(renderer: WebGLRenderer): void {
    if (!this.dirty) return;
    this.dirty = false;
    const { uniforms, state } = this;
    uniforms.uLean.value.set(this.lean.x, this.lean.y);
    uniforms.uStrength.value = state.strength;
    uniforms.uWeights.value = [state.w0, state.w1, state.w2, state.w3];
    renderer.setRenderTarget(this.target);
    renderer.render(this.pass, this.camera);
  }

  restore(): void {
    this.dirty = true;
  }
}
