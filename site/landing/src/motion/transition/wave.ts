import type { Point } from "../transition";

/** Where the curtain's edge is, as it passes: a circle round `from`, `radius` across, and how much its glass shows. */
export interface Edge {
  from: Point;
  radius: number;
  /** The front, the ink inside the circle; or the back, the ink outside it, the next page coming through. */
  front: boolean;
  /** 0 to 1. */
  glass: number;
}

const vertex = /* glsl */ `
  attribute vec2 aCorner;

  void main() {
    gl_Position = vec4(aCorner, 0.0, 1.0);
  }
`;

/**
 * The curtain's sheet: ink with the page's four lights low in it, exactly as Curtain.astro's still sheet draws them,
 * cut to a circle round uFrom (uRadius across, in CSS pixels from the screen's top left). Inside the circle as its
 * front comes over (uFront), outside it as its back comes through.
 *
 * Its edge is a lip of glass (uGlass), on the ink's side: the ink under it lifted, its colours richer; a glow catching
 * the colour of the light it's passing; a bright rim, warm just inside it and cool just outside; and a soft shadow
 * cast down onto the page. A little grain keeps the dark's gradients from banding.
 */
const fragment = /* glsl */ `
  precision highp float;

  uniform vec2 uBuffer;
  uniform vec2 uSize;
  uniform vec2 uFrom;
  uniform float uRadius;
  uniform float uFront;
  uniform float uGlass;
  uniform float uTime;

  const vec3 INK = vec3(13.0, 13.0, 18.0) / 255.0;
  const vec3 WARM = vec3(255.0, 120.0, 200.0) / 255.0;
  const vec3 COOL = vec3(96.0, 128.0, 255.0) / 255.0;
  const vec3 SHADOW = vec3(30.0, 40.0, 90.0) / 255.0;

  // One light over 'under': an ellipse round 'at', 'radius' across, its colour fading out by 70% of the way to its
  // rim, both as shares of the lights' box, which overhangs the screen by a tenth all round.
  vec3 light(vec3 under, vec2 p, vec2 at, vec2 radius, vec3 colour, float alpha) {
    vec2 box = uSize * 1.2;
    float t = length((p + uSize * 0.1 - box * at) / (box * radius));
    return mix(under, colour / 255.0, alpha * (1.0 - clamp(t / 0.7, 0.0, 1.0)));
  }

  // The ink and its lights, the first of Curtain.astro's over the rest.
  vec3 ink(vec2 p) {
    vec3 c = INK;
    c = light(c, p, vec2(0.22, 0.88), vec2(0.36, 0.38), vec3(150.0, 224.0, 200.0), 0.22);
    c = light(c, p, vec2(0.76, 0.86), vec2(0.40, 0.40), vec3(255.0, 186.0, 160.0), 0.24);
    c = light(c, p, vec2(0.84, 0.20), vec2(0.36, 0.42), vec3(196.0, 176.0, 255.0), 0.30);
    c = light(c, p, vec2(0.18, 0.24), vec2(0.38, 0.44), vec3(143.0, 180.0, 255.0), 0.34);
    return c;
  }

  // How far into the ink p is, in CSS pixels: negative on the page's side of the edge.
  float into(vec2 p) {
    float beyond = length(p - uFrom) - uRadius;
    return mix(beyond, -beyond, uFront);
  }

  float hash(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
  }

  void main() {
    vec2 p = vec2(gl_FragCoord.x, uBuffer.y - gl_FragCoord.y) * uSize / uBuffer;
    float pixel = uSize.x / uBuffer.x;
    float s = into(p);
    float covered = clamp(s / pixel + 0.5, 0.0, 1.0);

    vec3 lit = ink(p);
    vec3 c = lit;
    // The glass, its last 34px: the ink under it lifted and richer, more so towards the rim.
    float lens = (1.0 - smoothstep(10.0, 34.0, s)) * uGlass;
    float grey = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(c, mix(vec3(grey), c, 1.8) * 1.06, lens);
    // Its glow, white tinged with the colour of the light it's passing.
    vec3 caught = lit - INK;
    vec3 tint = mix(vec3(1.0), caught / max(max(caught.r, max(caught.g, caught.b)), 0.001), 0.35);
    c = mix(c, tint, 0.55 * (1.0 - smoothstep(-17.0, 27.0, s)) * uGlass);
    // A warm band inside the rim, and the rim, its colours parting a little either side of white.
    c = mix(c, WARM, 0.1 * (1.0 - smoothstep(4.0, 6.0, s)) * uGlass);
    vec3 rim = 1.0 - smoothstep(vec3(1.0, 1.25, 1.5), vec3(2.0, 2.4, 2.8), vec3(s));
    c = mix(c, vec3(1.0), 0.9 * rim * uGlass);
    c += (hash(gl_FragCoord.xy + fract(uTime) * 619.0) - 0.5) * 1.5 / 255.0;

    // On the page's side: the cool fringe, and the lip's shadow, cast 6px down.
    float fringe = 0.35 * (1.0 - smoothstep(1.0, 2.0, -s)) * uGlass;
    float shade = 0.22 * (1.0 - smoothstep(-30.0, 30.0, -into(p - vec2(0.0, 6.0)))) * uGlass;
    vec4 page = vec4(COOL * fringe, fringe) + vec4(SHADOW * shade, shade) * (1.0 - fringe);

    gl_FragColor = vec4(c, 1.0) * covered + page * (1.0 - covered);
  }
`;

type Uniform = "uBuffer" | "uSize" | "uFrom" | "uRadius" | "uFront" | "uGlass" | "uTime";

/**
 * The curtain's sheet as it passes (motion/transition.ts), drawn on the GPU in one pass, its edge and glass
 * included, so moving it costs a frame no layout, painting or filtering.
 */
export class Wave {
  private readonly uniforms: Record<Uniform, WebGLUniformLocation | null>;

  /** The sheet in `canvas`; null where WebGL isn't there. */
  static create(canvas: HTMLCanvasElement): Wave | null {
    const gl = canvas.getContext("webgl", {
      alpha: true,
      premultipliedAlpha: true,
      antialias: false,
      depth: false,
      stencil: false,
    });
    if (gl == null) return null;
    try {
      return new Wave(canvas, gl);
    } catch (error) {
      console.error(error);
      return null;
    }
  }

  private constructor(
    private readonly canvas: HTMLCanvasElement,
    private readonly gl: WebGLRenderingContext,
  ) {
    const program = gl.createProgram();
    gl.attachShader(program, this.shader(gl.VERTEX_SHADER, vertex));
    gl.attachShader(program, this.shader(gl.FRAGMENT_SHADER, fragment));
    gl.linkProgram(program);
    if (gl.getProgramParameter(program, gl.LINK_STATUS) !== true) {
      throw new Error(`The curtain's shader didn't link: ${gl.getProgramInfoLog(program) ?? ""}`);
    }
    gl.useProgram(program);
    // One triangle over the whole screen.
    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    const corner = gl.getAttribLocation(program, "aCorner");
    gl.enableVertexAttribArray(corner);
    gl.vertexAttribPointer(corner, 2, gl.FLOAT, false, 0, 0);
    const at = (name: Uniform) => gl.getUniformLocation(program, name);
    this.uniforms = {
      uBuffer: at("uBuffer"),
      uSize: at("uSize"),
      uFrom: at("uFrom"),
      uRadius: at("uRadius"),
      uFront: at("uFront"),
      uGlass: at("uGlass"),
      uTime: at("uTime"),
    };
    // Drawn once now, hidden, so the GPU has finished building the shader before the curtain first moves.
    this.draw({ from: { x: 0, y: 0 }, radius: 0, front: true, glass: 0 });
  }

  /** Whether it can still draw: the browser can take a page's WebGL away. */
  get usable(): boolean {
    return !this.gl.isContextLost();
  }

  /** Draws the sheet with its edge at `edge`. */
  draw(edge: Edge): void {
    const { gl, canvas, uniforms } = this;
    const width = window.innerWidth;
    const height = window.innerHeight;
    const ratio = Math.min(window.devicePixelRatio, 2);
    const across = Math.max(1, Math.round(width * ratio));
    const down = Math.max(1, Math.round(height * ratio));
    if (canvas.width !== across || canvas.height !== down) {
      canvas.width = across;
      canvas.height = down;
      gl.viewport(0, 0, across, down);
    }
    gl.uniform2f(uniforms.uBuffer, across, down);
    gl.uniform2f(uniforms.uSize, width, height);
    gl.uniform2f(uniforms.uFrom, edge.from.x, edge.from.y);
    gl.uniform1f(uniforms.uRadius, edge.radius);
    gl.uniform1f(uniforms.uFront, edge.front ? 1 : 0);
    gl.uniform1f(uniforms.uGlass, edge.glass);
    gl.uniform1f(uniforms.uTime, (performance.now() / 1000) % 1000);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }

  /** Gives back the GPU memory its frame takes while the curtain is away; it takes it again when next drawn. */
  rest(): void {
    this.canvas.width = 1;
    this.canvas.height = 1;
  }

  private shader(kind: number, source: string): WebGLShader {
    const { gl } = this;
    const shader = gl.createShader(kind);
    if (shader == null) throw new Error("WebGL made no shader");
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (gl.getShaderParameter(shader, gl.COMPILE_STATUS) !== true) {
      throw new Error(`The curtain's shader didn't compile: ${gl.getShaderInfoLog(shader) ?? ""}`);
    }
    return shader;
  }
}
