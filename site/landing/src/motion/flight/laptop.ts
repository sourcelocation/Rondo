import { mdiAirplane } from "@mdi/js";
import {
  type BufferGeometry,
  CanvasTexture,
  Group,
  type Material,
  Mesh,
  PlaneGeometry,
  ShaderMaterial,
  SRGBColorSpace,
  Vector3,
} from "three";
import { mergeGeometries } from "three/addons/utils/BufferGeometryUtils.js";
import logo from "../../../../../brand/dist/svg/logo.svg?raw";
import { crops } from "../../brand";
import { common, shapes, shared } from "./shading";
import { paint, slab } from "./shapes";

/**
 * The passenger's laptop, open on the table: a thin aluminium body with every key of its keyboard, a trackpad, and
 * its lid with a black bezel and a notch. On its screen, nothing but Rondo's logo beside the airplane-mode glyph.
 * It's built in its own frame (its base's underside centred on the origin, the user toward +z) and placed by the
 * cabin.
 */

/** The base's size, in metres: a thirteen-inch laptop. */
const BASE = { width: 0.304, depth: 0.215, height: 0.0113 } as const;
const LID = { length: 0.212, thickness: 0.0042 } as const;
/** How far the lid is open, past upright, in radians: 116° from closed. */
const OPEN = (116 * Math.PI) / 180;
/** The screen's size, and how far its middle is up the lid from the hinge. */
const SCREEN = { width: 0.29, height: 0.188, up: 0.1065 } as const;
/** One key's pitch, and the gap between keys. */
const PITCH = 0.0185;
const GAP = 0.0024;

/** The cabin shader's parts that the laptop is made of. */
export const LAPTOP_PART = { body: 4, lid: 5, key: 6 } as const;

/** The keyboard, row by row, in keys' widths; the function row's keys are shorter. */
const ROWS: readonly (readonly number[])[] = [
  Array.from({ length: 14 }, () => 14.5 / 14),
  [...Array.from({ length: 13 }, () => 1), 1.5],
  [1.5, ...Array.from({ length: 12 }, () => 1), 1],
  [1.75, ...Array.from({ length: 11 }, () => 1), 1.75],
  [2.25, ...Array.from({ length: 10 }, () => 1), 2.25],
  [1, 1, 1, 1.25, 5, 1.25, 1],
];

function keys(): BufferGeometry[] {
  const caps: BufferGeometry[] = [];
  const left = -(14.5 * PITCH) / 2;
  let z = -BASE.depth / 2 + 0.0105;
  for (const [row, widths] of ROWS.entries()) {
    const depth = row === 0 ? 0.0105 : PITCH;
    let x = left;
    for (const units of widths) {
      caps.push(cap(x + (units * PITCH) / 2, z + depth / 2, units * PITCH - GAP, depth - GAP));
      x += units * PITCH;
    }
    // The arrows, after the bottom row: left and right half height, up and down stacked between them.
    if (row === ROWS.length - 1) {
      const half = (PITCH - GAP) / 2 - GAP / 4;
      caps.push(cap(x + PITCH / 2, z + PITCH * 0.75, PITCH - GAP, half));
      caps.push(cap(x + PITCH * 1.5, z + PITCH * 0.25, PITCH - GAP, half));
      caps.push(cap(x + PITCH * 1.5, z + PITCH * 0.75, PITCH - GAP, half));
      caps.push(cap(x + PITCH * 2.5, z + PITCH * 0.75, PITCH - GAP, half));
    }
    z += depth;
  }
  return caps;
}

/** One key's cap, its middle at (x, z) on the base's top. */
function cap(x: number, z: number, width: number, depth: number): BufferGeometry {
  const geometry = slab(width, depth, 0.0011, 0.0024, 0.0005, LAPTOP_PART.key, { around: 20, profile: 3 });
  paint(geometry, LAPTOP_PART.key, 0.05, 0.35);
  geometry.translate(x, BASE.height - 0.0005, z);
  return geometry;
}

/** The lid, open: its outside aluminium, its inside the bezel's black glass (drawn by the shader). */
function lid(): BufferGeometry {
  const geometry = slab(BASE.width, LID.length, LID.thickness, 0.011, 0.0015, LAPTOP_PART.lid, { around: 72 });
  // Its own coordinates: across, and how far up from the hinge, for the bezel and the notch.
  paint(geometry, LAPTOP_PART.lid, 0.8, 0.6, (p) => [p.x, p.z + LID.length / 2, p.y]);
  geometry.translate(0, 0, LID.length / 2);
  geometry.rotateX(-OPEN);
  geometry.translate(0, BASE.height - 0.0006, -BASE.depth / 2 + 0.0035);
  return geometry;
}

/** The base, with its keyboard: the shader draws the keyboard's well and the trackpad on its top. */
function base(): BufferGeometry {
  const geometry = slab(BASE.width, BASE.depth, BASE.height, 0.012, 0.0022, LAPTOP_PART.body, { around: 80 });
  paint(geometry, LAPTOP_PART.body, 0.8, 0.6);
  return geometry;
}

/** The screen's picture: white, and on it Rondo's logo, a hairline, and the airplane-mode glyph. */
export function picture(): HTMLCanvasElement {
  const canvas = document.createElement("canvas");
  canvas.width = 2048;
  canvas.height = Math.round((2048 * SCREEN.height) / SCREEN.width);
  const context = canvas.getContext("2d");
  if (context == null) return canvas;
  context.fillStyle = "#ffffff";
  context.fillRect(0, 0, canvas.width, canvas.height);

  const [left = 0, top = 0, width = 1, height = 1] = crops.logo.split(" ").map(Number);
  const logoHeight = canvas.height * 0.12;
  const scale = logoHeight / height;
  const logoWidth = width * scale;
  const glyph = logoHeight * 1.04;
  const gap = logoHeight * 0.7;
  const x = (canvas.width - (logoWidth + 2 * gap + glyph)) / 2;
  const y = (canvas.height - logoHeight) / 2;

  context.fillStyle = "#0b0b0d";
  context.save();
  context.translate(x, y);
  context.scale(scale, scale);
  context.translate(-left, -top);
  // The wordmark's letters, then the mark, which the artwork moves into place in a group of its own.
  const [letters = "", mark = ""] = logo.split(/<g[^>]*transform=/u);
  for (const [, path] of letters.matchAll(/<path d="([^"]+)"/gu)) if (path != null) context.fill(new Path2D(path));
  const shift = /translate\(([-\d.]+),\s*([-\d.]+)\)/u.exec(mark);
  context.translate(Number(shift?.[1] ?? 0), Number(shift?.[2] ?? 0));
  for (const [, path] of mark.matchAll(/<path d="([^"]+)"/gu)) {
    if (path != null) context.fill(new Path2D(path), "evenodd");
  }
  context.restore();

  context.fillStyle = "#c4c5ca";
  context.fillRect(x + logoWidth + gap - 1.5, y + logoHeight * 0.1, 3, logoHeight * 0.8);

  // The glyph's own box runs from about 3.3 to 21.2 of its 24.
  context.save();
  context.translate(x + logoWidth + 2 * gap + glyph / 2, y + logoHeight / 2);
  context.scale(glyph / 17.9, glyph / 17.9);
  context.translate(-12.25, -12.0);
  context.fillStyle = "#0b0b0d";
  context.fill(new Path2D(mdiAirplane));
  context.restore();
  return canvas;
}

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
    // The picture glows; the notch at the top is the bezel's black.
    float lit = texture(uPicture, vUv).r;
    vec2 p = (vUv - vec2(0.5, 1.0)) * vec2(${SCREEN.width}, ${SCREEN.height});
    float notch = roundedBox(p, vec2(0.0165, 0.0058), 0.0035);
    float black = 1.0 - smoothstep(-fwidth(notch), fwidth(notch), notch);
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
  /** The screen's middle, and the way it faces, in the laptop's own frame. */
  static readonly screen = {
    centre: new Vector3(
      0,
      BASE.height - 0.0006 + SCREEN.up * Math.sin(OPEN),
      -BASE.depth / 2 + 0.0035 + SCREEN.up * Math.cos(OPEN),
    ),
    normal: new Vector3(0, -Math.cos(OPEN), Math.sin(OPEN)),
  };
  /** The laptop's footprint on the table: half its width and depth. */
  static readonly footprint = { halfWidth: BASE.width / 2, halfDepth: BASE.depth / 2 } as const;

  constructor(body: Material, anisotropy: number) {
    const parts = [base(), lid(), ...keys()];
    const geometry = mergeGeometries(parts);
    for (const part of parts) part.dispose();
    const mesh = new Mesh(geometry, body);
    mesh.renderOrder = 0;
    this.group.add(mesh);

    const texture = new CanvasTexture(picture());
    texture.colorSpace = SRGBColorSpace;
    texture.anisotropy = anisotropy;
    const screen = new Mesh(
      new PlaneGeometry(SCREEN.width, SCREEN.height),
      new ShaderMaterial({
        vertexShader: screenVertex,
        fragmentShader: screenFragment,
        uniforms: { ...shared, uPicture: { value: texture }, uGlow: this.glow },
      }),
    );
    // Face down along the lid while it's closed, then open with it.
    screen.rotation.x = Math.PI / 2;
    screen.position.set(0, -0.0003, SCREEN.up);
    const hinge = new Group();
    hinge.position.set(0, BASE.height - 0.0006, -BASE.depth / 2 + 0.0035);
    hinge.rotation.x = -OPEN;
    hinge.add(screen);
    this.group.add(hinge);
  }
}
