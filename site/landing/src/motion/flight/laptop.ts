import { type BufferGeometry, Group, type Material, Mesh, PlaneGeometry, ShaderMaterial, Vector3 } from "three";
import { common, shapes, shared } from "./shading";
import { Study } from "./study";

/**
 * The passenger's laptop, open on the table: a sixteen-inch MacBook Pro, its aluminium body with a black keyboard
 * of full-height keys, speaker grilles either side, a wide trackpad, and its lid with thin black borders and a
 * notch. On its screen, Rondo, studying (study.ts). It's made in Blender (motion/flight/model.ts) in its own frame (its
 * base's underside centred on the origin, the user toward +z), and placed by the cabin.
 */

/** The base's size, in metres, and the lid's length. */
const BASE = { width: 0.3557, depth: 0.2481, height: 0.0118 } as const;
const LID = { length: 0.2465 } as const;
/** How far the lid is open, past upright, in radians: 116° from closed. */
const OPEN = (116 * Math.PI) / 180;
/** Where the lid turns, from the base's back edge. */
const HINGE = 0.0035;
/** The black glass inside the lid: how far in from the lid's sides and top it stops, and how far up from the hinge. */
const GLASS = { inset: 0.0022, bottom: 0.004 } as const;
/** How much of the glass is black round the screen, the same at its sides as at its top. */
const BEZEL = 0.0054;
/**
 * The screen's size (as tall as a sixteen-inch MacBook Pro's, as wide as the glass leaves it), and how far its middle
 * is up the lid from the hinge: the bezel above it, the deeper chin below.
 */
const SCREEN = {
  width: BASE.width - 2 * (GLASS.inset + BEZEL),
  height: 0.2234,
  up: LID.length - GLASS.inset - BEZEL - 0.2234 / 2,
} as const;
/** The notch in the screen's top: half its width, its depth and its corners' rounding. */
const NOTCH = { half: 0.0155, depth: 0.0076, corner: 0.0035 } as const;
/** The screen's corners' rounding, following the lid's glass around it. */
const SCREEN_CORNER = 0.008;
/** One key's pitch. */
const PITCH = 0.019;
/** How far in from the base's back edge the keyboard starts, and from its front edge the trackpad ends. */
const MARGIN = { back: 0.017, front: 0.0102 } as const;
/** The trackpad's size. */
const TRACKPAD = { width: 0.16, depth: 0.099 } as const;

/** The cabin shader's parts that the laptop is made of, the model's `part`. */
export const LAPTOP_PART = { body: 4, lid: 5 } as const;

/** The keyboard's width (fourteen and a half keys), and where its six rows start and end, front to back. */
const KEYBOARD = {
  width: 14.5 * PITCH,
  back: -BASE.depth / 2 + MARGIN.back,
  front: -BASE.depth / 2 + MARGIN.back + 6 * PITCH,
} as const;

/** The keyboard's well, a little wider than its keys, and the grilles beside it. */
const WELL = {
  half: KEYBOARD.width / 2 + 0.0035,
  middle: (KEYBOARD.back + KEYBOARD.front) / 2,
  depth: (KEYBOARD.front - KEYBOARD.back) / 2 + 0.0035,
} as const;
const GRILLE = { inner: WELL.half + 0.0075, outer: BASE.width / 2 - 0.0095 } as const;

const screenVertex = /* glsl */ `
  varying vec2 vUv;
  varying vec3 vWorld;
  varying vec3 vNormal;

  void main() {
    vUv = uv;
    vec4 world = modelMatrix * vec4(position, 1.0);
    vWorld = world.xyz;
    vNormal = normalize(mat3(modelMatrix) * normal);
    gl_Position = projectionMatrix * viewMatrix * world;
  }
`;

const screenFragment = /* glsl */ `
  ${common}
  ${shapes}
  uniform sampler2D uPicture;
  uniform float uGlow;
  varying vec2 vUv;
  varying vec3 vWorld;
  varying vec3 vNormal;

  void main() {
    // The picture glows; the notch at the top and past its rounded corners is the bezel's black.
    float lit = texture(uPicture, vUv).r;
    vec2 p = (vUv - vec2(0.5, 1.0)) * vec2(${SCREEN.width}, ${SCREEN.height});
    float notch = roundedBox(p, vec2(${NOTCH.half}, ${NOTCH.depth}), ${NOTCH.corner});
    float black = 1.0 - smoothstep(-fwidth(notch), fwidth(notch), notch);
    vec2 extent = vec2(${SCREEN.width / 2}, ${SCREEN.height / 2});
    float edge = roundedBox((vUv - 0.5) * extent * 2.0, extent, ${SCREEN_CORNER});
    black = max(black, smoothstep(-fwidth(edge), fwidth(edge), edge));
    vec3 n = normalize(vNormal);
    vec3 view = normalize(cameraPosition - vWorld);
    float fresnel = 0.04 + 0.96 * pow(1.0 - max(dot(n, view), 0.0), 5.0);
    // The glass mirrors the bright cabin behind the camera, faintly, more so at a slant.
    float radiance = lit * 2.7 * uGlow * (1.0 - black) + 0.015 + fresnel * 1.4;
    gl_FragColor = finish(vec3(radiance));
  }
`;

export class Laptop {
  readonly group = new Group();
  /** How bright the screen is. */
  readonly glow = { value: 1 };
  /** What's on the screen. */
  readonly study: Study;
  /** The screen's middle, the way it faces, in the laptop's own frame, and its size. */
  static readonly screen = {
    centre: new Vector3(
      0,
      BASE.height - 0.0006 + SCREEN.up * Math.sin(OPEN),
      -BASE.depth / 2 + HINGE + SCREEN.up * Math.cos(OPEN),
    ),
    normal: new Vector3(0, -Math.cos(OPEN), Math.sin(OPEN)),
    width: SCREEN.width,
    height: SCREEN.height,
  };
  /**
   * How the cabin's shader draws the laptop's surfaces: on the base's top, the keyboard's black well and its keys
   * (uKeys, their picture), the speaker grilles' holes and the trackpad's edge; inside the lid, the black glass within
   * an aluminium rim.
   */
  static readonly surface = /* glsl */ `
    } else if (part == ${LAPTOP_PART.body}) {
      if (vSurface.y > ${BASE.height - 0.001}) {
        vec2 top = vSurface.xz;
        float well = roundedBox(top - vec2(0.0, ${WELL.middle}), vec2(${WELL.half}, ${WELL.depth}), 0.005);
        float inWell = 1.0 - smoothstep(-0.0003, 0.0003, well);
        // The keys, from their picture (model.ts) over the well: its red and green the way each faces across and
        // toward the user, its blue where the caps are, black and a little glossier than the well between them.
        vec2 at = vec2(
          (top.x + ${WELL.half}) / ${2 * WELL.half},
          (${WELL.middle + WELL.depth} - top.y) / ${2 * WELL.depth}
        );
        vec3 keys = texture(uKeys, at).rgb;
        vec2 slope = keys.rg * 2.0 - 1.0;
        vec3 key = vec3(slope.x, sqrt(max(1.0 - dot(slope, slope), 0.0)), slope.y);
        // Turned with the laptop, from its own frame into the cabin's.
        key = vec3(uLaptop.z * key.x + uLaptop.w * key.z, key.y, -uLaptop.w * key.x + uLaptop.z * key.z);
        n = normalize(mix(n, key, inWell));
        albedo = mix(albedo, mix(0.035, 0.04, keys.b), inWell);
        gloss = mix(gloss, mix(0.3, 0.4, keys.b), inWell);
        float grille = roundedBox(
          vec2(abs(top.x) - ${(GRILLE.inner + GRILLE.outer) / 2}, top.y - ${WELL.middle}),
          vec2(${(GRILLE.outer - GRILLE.inner) / 2}, ${WELL.depth - 0.002}),
          0.002
        );
        vec2 cell = fract(top / 0.0016) - 0.5;
        float hole = length(cell) * 0.0016 - 0.00042;
        float holes = (1.0 - smoothstep(-fwidth(hole), fwidth(hole), hole)) * (1.0 - smoothstep(-0.0002, 0.0002, grille));
        albedo *= 1.0 - 0.8 * holes;
        float pad = roundedBox(
          top - vec2(0.0, ${BASE.depth / 2 - MARGIN.front - TRACKPAD.depth / 2}),
          vec2(${TRACKPAD.width / 2}, ${TRACKPAD.depth / 2}),
          0.008
        );
        albedo *= 1.0 - 0.3 * stroke(pad, 0.00025);
        gloss = mix(gloss, 0.75, 1.0 - smoothstep(-0.0004, 0.0004, pad));
      }
    } else if (part == ${LAPTOP_PART.lid}) {
      vec2 face = vec2(vSurface.x, vSurface.y - ${(GLASS.bottom + LID.length - GLASS.inset) / 2});
      vec2 glass = vec2(${BASE.width / 2 - GLASS.inset}, ${(LID.length - GLASS.inset - GLASS.bottom) / 2});
      if (vSurface.z < 0.0006 && roundedBox(face, glass, 0.011) < 0.0) {
        albedo = 0.02;
        gloss = 0.9;
      }
  `;
  /** The laptop's footprint on the table: half its width and depth. */
  static readonly footprint = { halfWidth: BASE.width / 2, halfDepth: BASE.depth / 2 } as const;

  /** The laptop, of `geometry` (the model's laptop: model.ts), painted by `body` (the cabin's). */
  constructor(body: Material, anisotropy: number, geometry: BufferGeometry) {
    const mesh = new Mesh(geometry, body);
    mesh.renderOrder = 0;
    this.group.add(mesh);

    this.study = new Study(1536, SCREEN.height / SCREEN.width, anisotropy);
    const screen = new Mesh(
      new PlaneGeometry(SCREEN.width, SCREEN.height),
      new ShaderMaterial({
        vertexShader: screenVertex,
        fragmentShader: screenFragment,
        uniforms: { ...shared, uPicture: { value: this.study.texture }, uGlow: this.glow },
      }),
    );
    // Face down along the lid while it's closed, then open with it.
    screen.rotation.x = Math.PI / 2;
    screen.position.set(0, -0.0003, SCREEN.up);
    const hinge = new Group();
    hinge.position.set(0, BASE.height - 0.0006, -BASE.depth / 2 + HINGE);
    hinge.rotation.x = -OPEN;
    hinge.add(screen);
    this.group.add(hinge);
  }
}
