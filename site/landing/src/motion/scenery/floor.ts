import { BufferAttribute, BufferGeometry, Mesh, ShaderMaterial, Vector2 } from "three";

const vertex = /* glsl */ `
  attribute vec2 aCorner;
  uniform vec2 uViewport;
  uniform vec2 uCentre;
  uniform vec2 uExtent;
  varying vec2 vFace;

  void main() {
    // Room round the ellipse, for it to fade out smoothly.
    vFace = (aCorner * 2.0 - 1.0) * (uExtent + 12.0);
    vec2 p = uCentre + vFace;
    gl_Position = vec4(p.x / uViewport.x * 2.0 - 1.0, 1.0 - p.y / uViewport.y * 2.0, 0.0, 1.0);
  }
`;

const fragment = /* glsl */ `
  uniform vec2 uExtent;
  uniform float uDepth;
  varying vec2 vFace;

  const vec3 SHADE = vec3(0.118, 0.157, 0.314);

  void main() {
    float a = 0.22 * clamp(1.0 - length(vFace / uExtent), 0.0, 1.0);
    gl_FragColor = vec4(SHADE, 1.0) * a * uDepth;
  }
`;

/** The soft shadow the collection's deck stands on, under its foot, as its CSS drew it. */
export class Floor {
  readonly mesh: Mesh;
  /** How deep the shadow is, from 0 to 1. */
  depth = 0.4;
  private readonly uniforms = {
    uViewport: { value: new Vector2(1, 1) },
    uCentre: { value: new Vector2() },
    uExtent: { value: new Vector2(1, 1) },
    uDepth: { value: 0 },
  };

  constructor() {
    const geometry = new BufferGeometry();
    geometry.setAttribute("aCorner", new BufferAttribute(new Float32Array([0, 0, 1, 0, 0, 1, 1, 1]), 2));
    geometry.setIndex([0, 1, 2, 2, 1, 3]);
    this.mesh = new Mesh(
      geometry,
      new ShaderMaterial({
        vertexShader: vertex,
        fragmentShader: fragment,
        uniforms: this.uniforms,
        transparent: true,
        premultipliedAlpha: true,
        depthTest: false,
        depthWrite: false,
      }),
    );
    this.mesh.frustumCulled = false;
  }

  setViewport(width: number, height: number): void {
    this.uniforms.uViewport.value.set(width, height);
  }

  /**
   * Lays the shadow under a deck whose front's top left is at (`left`, `top`) on the window, a card `width` ×
   * `height`, showing as much as `fade` (0 to 1).
   */
  place(left: number, top: number, width: number, height: number, fade: number): void {
    const { uniforms } = this;
    uniforms.uCentre.value.set(left + width / 2, top + height * 0.97);
    uniforms.uExtent.value.set(width * 0.85, height * 0.15);
    uniforms.uDepth.value = this.depth * fade;
  }
}
