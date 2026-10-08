import { gsap } from "gsap";
import { Mesh, Program, Renderer, Triangle } from "ogl";

/** The page's moods: how strongly the light shows behind each section, and in which colours. */
export const moods = {
  hero: { strength: 1, weights: [1, 0.9, 0.85, 0.7] },
  anki: { strength: 0.5, weights: [0.55, 0.95, 1, 0.35] },
  source: { strength: 0.36, weights: [0.75, 0.45, 0.25, 1] },
  sync: { strength: 0.5, weights: [1, 0.45, 0.3, 0.95] },
  apps: { strength: 0.48, weights: [0.85, 1, 0.55, 0.45] },
  maker: { strength: 0.34, weights: [0.45, 0.65, 1, 0.5] },
  closing: { strength: 1, weights: [1, 0.95, 0.9, 0.75] },
} as const;

export type Mood = keyof typeof moods;

export function isMood(name: string | undefined): name is Mood {
  return name != null && Object.hasOwn(moods, name);
}

const vertex = /* glsl */ `
  attribute vec2 uv;
  attribute vec2 position;
  varying vec2 vUv;

  void main() {
    vUv = uv;
    gl_Position = vec4(position, 0.0, 1.0);
  }
`;

const fragment = /* glsl */ `
  precision highp float;

  uniform float uTime;
  uniform vec2 uResolution;
  uniform vec2 uPointer;
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

    // The lights drift on slow loops, and the space they sit in folds gently.
    vec2 q = p + 0.16 * vec2(snoise(p * 1.2 + vec2(t, -t)), snoise(p * 1.2 + vec2(4.7 - t, t)));
    vec2 lean = (uPointer - 0.5) * vec2(aspect, 1.0) * 0.1;
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

    // A trace of noise keeps such soft gradients from banding; plain white stays plain.
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
 * The light behind the page, drawn by a WebGL shader at a fraction of the screen's resolution (it is all soft):
 * four pastel lights that drift, lean toward the pointer and change with each section's mood.
 */
export class Aurora {
  private readonly renderer: Renderer;
  private readonly mesh: Mesh;
  private readonly uniforms = {
    uTime: { value: 0 },
    uResolution: { value: [1, 1] },
    uPointer: { value: [0.5, 0.5] },
    uStrength: { value: 0 },
    uWeights: { value: [1, 1, 1, 1] },
  };
  private readonly state = { strength: 0, w0: 1, w1: 1, w2: 1, w3: 1 };
  private readonly pointer = { x: 0.5, y: 0.5 };
  private time = 0;
  private dirty = true;

  /** Draws the light into `host`, or leaves its CSS gradients there when WebGL isn't available. */
  static mount(host: HTMLElement, still: boolean): Aurora | null {
    try {
      return new Aurora(host, still);
    } catch {
      return null;
    }
  }

  private constructor(
    host: HTMLElement,
    private readonly still: boolean,
  ) {
    this.renderer = new Renderer({ dpr: 0.5, alpha: false, antialias: false, powerPreference: "low-power" });
    const gl = this.renderer.gl;
    const styles = getComputedStyle(document.documentElement);
    const colour = (name: string) => rgb(styles.getPropertyValue(name).trim());
    const program = new Program(gl, {
      vertex,
      fragment,
      uniforms: {
        ...this.uniforms,
        uSky: { value: colour("--sky") },
        uLilac: { value: colour("--lilac") },
        uPeach: { value: colour("--peach") },
        uMint: { value: colour("--mint") },
      },
    });
    this.mesh = new Mesh(gl, { geometry: new Triangle(gl), program });
    host.append(gl.canvas);
    host.classList.add("is-webgl");
    this.resize();
    window.addEventListener("resize", () => this.resize());
    if (!still) {
      window.addEventListener(
        "pointermove",
        (event) => {
          gsap.to(this.pointer, {
            x: event.clientX / window.innerWidth,
            y: 1 - event.clientY / window.innerHeight,
            duration: 2.4,
            ease: "power2.out",
            overwrite: true,
          });
        },
        { passive: true },
      );
    }
    gsap.ticker.add(this.tick);
  }

  /** Turns the light toward a mood, gradually (at once with reduced motion). */
  mood(name: Mood, duration = 2): void {
    const { strength, weights } = moods[name];
    const target = { strength, w0: weights[0], w1: weights[1], w2: weights[2], w3: weights[3] };
    if (this.still) {
      Object.assign(this.state, target);
      this.dirty = true;
      return;
    }
    gsap.to(this.state, { ...target, duration, ease: "sine.inOut", overwrite: true });
  }

  private resize(): void {
    this.renderer.setSize(window.innerWidth, window.innerHeight);
    this.uniforms.uResolution.value = [window.innerWidth, window.innerHeight];
    this.dirty = true;
  }

  private readonly tick = (_time: number, deltaMs: number): void => {
    const visible = this.state.strength > 0.002;
    if (this.still ? !this.dirty : !visible && !this.dirty) return;
    if (!this.still) this.time += Math.min(deltaMs, 50) / 1000;
    const { uniforms, state } = this;
    uniforms.uTime.value = this.time + 40;
    uniforms.uPointer.value = [this.pointer.x, this.pointer.y];
    uniforms.uStrength.value = state.strength;
    uniforms.uWeights.value = [state.w0, state.w1, state.w2, state.w3];
    this.renderer.render({ scene: this.mesh });
    this.dirty = false;
  };
}
