import {
  DepthTexture,
  LinearFilter,
  Mesh,
  OrthographicCamera,
  type PerspectiveCamera,
  Scene,
  ShaderMaterial,
  UnsignedIntType,
  Vector2,
  Vector3,
  WebGLRenderTarget,
  type WebGLRenderer,
} from "three";
import { cover } from "./clouds";

/**
 * Depth of field, as a fast lens focused on the laptop has it: what's at the screen's distance sharp, the cabin beyond
 * it melting into soft, rounded blurs, more the farther it is. The scene is drawn into a texture with its depth; a
 * pass shrinks it to half size, keeping beside each pixel's colour how blurred it is (and whether in front of the
 * focus or behind it); a second gathers each pixel's blur from a spiral of samples around it in that half-size
 * image (each counting as far as its own blur reaches, so a sharp edge doesn't smear over what's behind it); and the
 * sharp frame and the blurred one are blended pixel by pixel, by how far out of focus each is.
 */

const fullscreen = /* glsl */ `
  varying vec2 vUv;

  void main() {
    vUv = position.xy * 0.5 + 0.5;
    gl_Position = vec4(position.xy, 0.0, 1.0);
  }
`;

/** Depth to distance, and how far out of focus that is: -1 to 1, below 0 in front of the focus. */
const lens = /* glsl */ `
  uniform sampler2D uDepth;
  uniform float uNear;
  uniform float uFar;
  uniform float uFocus;
  uniform float uBlur;

  float distanceAt(vec2 uv) {
    float d = texture(uDepth, uv).r * 2.0 - 1.0;
    return 2.0 * uNear * uFar / (uFar + uNear - d * (uFar - uNear));
  }

  float defocus(float distance) {
    return clamp(1.0 - uFocus / distance, -1.0, 1.0);
  }
`;

/** The half-size image: colour, and its defocus in alpha (0 to 1 for -1 to 1). */
const shrink = /* glsl */ `
  ${lens}
  uniform sampler2D uColor;
  varying vec2 vUv;

  void main() {
    gl_FragColor = vec4(texture(uColor, vUv).rgb, 0.5 + 0.5 * defocus(distanceAt(vUv)));
  }
`;

const gather = /* glsl */ `
  uniform sampler2D uHalf;
  uniform vec2 uTexel;
  uniform float uBlur;
  uniform float uSpacing;
  varying vec2 vUv;

  const float GOLDEN = 2.39996323;

  void main() {
    vec4 centre = texture(uHalf, vUv);
    float away = centre.a * 2.0 - 1.0;
    float size = uBlur * abs(away);
    vec3 colour = centre.rgb;
    float total = 1.0;
    float radius = 1.0;
    for (int i = 0; i < 200; i++) {
      if (radius >= uBlur) break;
      float angle = float(i) * GOLDEN;
      vec4 tap = texture(uHalf, vUv + vec2(cos(angle), sin(angle)) * uTexel * radius);
      float tapAway = tap.a * 2.0 - 1.0;
      float reach = uBlur * abs(tapAway);
      // What's behind the centre blurs no further over it than the centre's own blur.
      if (tapAway > away) reach = min(reach, size * 2.0);
      float counts = smoothstep(radius - 0.5, radius + 0.5, reach);
      colour += mix(colour / total, tap.rgb, counts);
      total += 1.0;
      radius += uSpacing / radius;
    }
    gl_FragColor = vec4(colour / total, 1.0);
  }
`;

const blend = /* glsl */ `
  ${lens}
  uniform sampler2D uSharp;
  uniform sampler2D uSoft;
  varying vec2 vUv;

  void main() {
    float size = uBlur * abs(defocus(distanceAt(vUv)));
    vec3 sharp = texture(uSharp, vUv).rgb;
    vec3 soft = texture(uSoft, vUv).rgb;
    gl_FragColor = vec4(mix(sharp, soft, smoothstep(0.35, 1.4, size)), 1.0);
  }
`;

export class Focus {
  private readonly sharp = new WebGLRenderTarget(1, 1, { samples: 4, generateMipmaps: false });
  private readonly half = new WebGLRenderTarget(1, 1, { depthBuffer: false, generateMipmaps: false });
  private readonly soft = new WebGLRenderTarget(1, 1, { depthBuffer: false, generateMipmaps: false });
  private readonly pass = new Scene();
  private readonly quad: Mesh;
  private readonly flat = new OrthographicCamera();
  private readonly shrink: ShaderMaterial;
  private readonly gather: ShaderMaterial;
  private readonly blend: ShaderMaterial;
  private readonly lens = {
    uDepth: { value: null as DepthTexture | null },
    uNear: { value: 0.03 },
    uFar: { value: 9000 },
    uFocus: { value: 1 },
    uBlur: { value: 0 },
  };
  private readonly texel = new Vector2();
  private readonly point = new Vector3();
  private height = 1;

  /** `light` gathers fewer samples, more widely spaced: for small screens and slower devices. */
  constructor(
    private readonly camera: PerspectiveCamera,
    light: boolean,
  ) {
    const depth = new DepthTexture(1, 1, UnsignedIntType);
    this.sharp.depthTexture = depth;
    this.lens.uDepth.value = depth;
    for (const target of [this.half, this.soft]) {
      target.texture.minFilter = LinearFilter;
      target.texture.magFilter = LinearFilter;
    }
    const pass = (fragmentShader: string, uniforms: Record<string, { value: unknown }>) =>
      new ShaderMaterial({ vertexShader: fullscreen, fragmentShader, uniforms, depthTest: false, depthWrite: false });
    this.shrink = pass(shrink, { ...this.lens, uColor: { value: this.sharp.texture } });
    this.gather = pass(gather, {
      uHalf: { value: this.half.texture },
      uTexel: { value: this.texel },
      uBlur: this.lens.uBlur,
      uSpacing: { value: light ? 3.2 : 2.0 },
    });
    this.blend = pass(blend, {
      ...this.lens,
      uSharp: { value: this.sharp.texture },
      uSoft: { value: this.soft.texture },
    });
    this.quad = new Mesh(cover(), this.gather);
    this.quad.frustumCulled = false;
    this.pass.add(this.quad);
  }

  setSize(width: number, height: number): void {
    this.height = height;
    this.sharp.setSize(width, height);
    const halfWidth = Math.max(1, Math.round(width / 2));
    const halfHeight = Math.max(1, Math.round(height / 2));
    this.half.setSize(halfWidth, halfHeight);
    this.soft.setSize(halfWidth, halfHeight);
    // A step of the gather is a pixel of the half-size image it reads.
    this.texel.set(1 / halfWidth, 1 / halfHeight);
  }

  /** Draws `scene` to the screen focused on `point`, blurred as much as `strength` (0 to 1) allows. */
  render(renderer: WebGLRenderer, scene: Scene, strength: number, point: Vector3): void {
    const { camera } = this;
    this.lens.uNear.value = camera.near;
    this.lens.uFar.value = camera.far;
    this.lens.uFocus.value = Math.max(-this.point.copy(point).applyMatrix4(camera.matrixWorldInverse).z, 0.05);
    // The widest blur, in pixels of the half-size image, at full strength: as a fast lens wide open has it, about a
    // twenty-fifth of the frame's height.
    this.lens.uBlur.value = Math.max((this.height / 2) * 0.04 * strength, 0.01);

    renderer.setRenderTarget(this.sharp);
    renderer.render(scene, camera);
    this.quad.material = this.shrink;
    renderer.setRenderTarget(this.half);
    renderer.render(this.pass, this.flat);
    this.quad.material = this.gather;
    renderer.setRenderTarget(this.soft);
    renderer.render(this.pass, this.flat);
    this.quad.material = this.blend;
    renderer.setRenderTarget(null);
    renderer.render(this.pass, this.flat);
  }
}
