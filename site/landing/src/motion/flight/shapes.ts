import { BufferAttribute, BufferGeometry, Vector3 } from "three";

/**
 * Building blocks for the flight's models, which are made in code rather than loaded: every part is a smooth surface
 * given by a function of two parameters (along and around it), sampled as a grid. Normals come from the function
 * itself, so seams and tips stay smooth. Each vertex also carries the surface's own coordinates (`surface`) and
 * which part it belongs to (`part`), for the shaders to draw details (windows, panel lines, hinges) into it.
 */

/** Where the point of a surface at (u, v) is. */
export type Surface = (u: number, v: number, out: Vector3) => void;

export interface GridOptions {
  /** Which part of the model this is, for its shader. */
  readonly part: number;
  /** The surface's own coordinates at (u, v), for its shader to draw details by. */
  readonly params?: (u: number, v: number) => readonly [number, number, number];
  /** Turns the faces (and their normals) the other way, for surfaces seen from inside. */
  readonly flip?: boolean;
}

const at = new Vector3();
const forward = new Vector3();
const back = new Vector3();
const alongU = new Vector3();
const alongV = new Vector3();
const normal = new Vector3();

/**
 * A surface as a grid of vertices at every pair of `us` and `vs` (each in increasing order), with normals from the
 * surface's derivatives, measured within its own domain so a tip or an edge has the normal of the face it ends.
 */
export function grid(
  us: readonly number[],
  vs: readonly number[],
  surface: Surface,
  options: GridOptions,
): BufferGeometry {
  const columns = us.length;
  const rows = vs.length;
  const count = columns * rows;
  const positions = new Float32Array(count * 3);
  const normals = new Float32Array(count * 3);
  const params = new Float32Array(count * 3);
  const parts = new Float32Array(count).fill(options.part);
  const uMin = us[0] ?? 0;
  const uMax = us[columns - 1] ?? 1;
  const vMin = vs[0] ?? 0;
  const vMax = vs[rows - 1] ?? 1;
  const du = (uMax - uMin) * 1e-4;
  const dv = (vMax - vMin) * 1e-4;
  const sign = options.flip === true ? -1 : 1;

  let index = 0;
  for (const u of us) {
    for (const v of vs) {
      surface(u, v, at);
      positions[index * 3] = at.x;
      positions[index * 3 + 1] = at.y;
      positions[index * 3 + 2] = at.z;

      derivative(surface, Math.max(u - du, uMin), Math.min(u + du, uMax), v, true, alongU);
      derivative(surface, Math.max(v - dv, vMin), Math.min(v + dv, vMax), u, false, alongV);
      normal.crossVectors(alongU, alongV);
      // A tip (where the surface closes to a point) has no width: its normal is the direction it ends in.
      if (normal.lengthSq() < 1e-14) normal.copy(alongU).negate();
      normal.normalize().multiplyScalar(sign);
      normals[index * 3] = normal.x;
      normals[index * 3 + 1] = normal.y;
      normals[index * 3 + 2] = normal.z;

      if (options.params != null) params.set(options.params(u, v), index * 3);
      index++;
    }
  }

  const indices: number[] = [];
  for (let column = 0; column < columns - 1; column++) {
    for (let row = 0; row < rows - 1; row++) {
      const a = column * rows + row;
      const b = (column + 1) * rows + row;
      const c = b + 1;
      const d = a + 1;
      if (sign > 0) indices.push(a, b, c, a, c, d);
      else indices.push(a, c, b, a, d, c);
    }
  }

  const geometry = new BufferGeometry();
  geometry.setAttribute("position", new BufferAttribute(positions, 3));
  geometry.setAttribute("normal", new BufferAttribute(normals, 3));
  geometry.setAttribute("surface", new BufferAttribute(params, 3));
  geometry.setAttribute("part", new BufferAttribute(parts, 1));
  geometry.setIndex(indices);
  return geometry;
}

/** The surface's derivative along u (or v) between `from` and `to`, holding the other parameter at `other`. */
function derivative(
  surface: Surface,
  from: number,
  to: number,
  other: number,
  alongFirst: boolean,
  out: Vector3,
): void {
  if (alongFirst) {
    surface(to, other, forward);
    surface(from, other, back);
  } else {
    surface(other, to, forward);
    surface(other, from, back);
  }
  out.subVectors(forward, back).divideScalar(Math.max(to - from, 1e-9));
}

/** `count` values from `from` to `to`, evenly spaced. */
export function steps(from: number, to: number, count: number): number[] {
  return Array.from({ length: count }, (_, index) => from + ((to - from) * index) / (count - 1));
}

/**
 * `count` values from `from` to `to`, closer together toward either end (`bunch` 0 is even, 1 bunches them as a
 * cosine does), where a surface curves the most.
 */
export function bunched(from: number, to: number, count: number, bunch = 1): number[] {
  return Array.from({ length: count }, (_, index) => {
    const t = index / (count - 1);
    const eased = 0.5 - 0.5 * Math.cos(Math.PI * t);
    return from + (to - from) * (t + (eased - t) * bunch);
  });
}

/** A smooth curve through 2D points: where it is a share `t` (0 to 1) of the way along, by the points' order. */
export function spline(points: readonly (readonly [number, number])[]): (t: number) => [number, number] {
  const last = points.length - 1;
  const point = (index: number) => points[Math.min(Math.max(index, 0), last)] ?? [0, 0];
  return (t) => {
    const position = Math.min(Math.max(t, 0), 1) * last;
    const index = Math.min(Math.floor(position), last - 1);
    const f = position - index;
    const p0 = point(index - 1);
    const p1 = point(index);
    const p2 = point(index + 1);
    const p3 = point(index + 2);
    const blend = (a: number, b: number, c: number, d: number) =>
      0.5 * (2 * b + (-a + c) * f + (2 * a - 5 * b + 4 * c - d) * f * f + (-a + 3 * b - 3 * c + d) * f * f * f);
    return [blend(p0[0], p1[0], p2[0], p3[0]), blend(p0[1], p1[1], p2[1], p3[1])];
  };
}

/**
 * A point around an airfoil, `t` of the way from its trailing edge over the top (0) to its leading edge (0.5) and
 * back under the bottom (1), as [along the chord from the leading edge, up], in chords. NACA's four-digit shape,
 * `thickness` thick and with `camber`, which bunches points at the nose where it curves.
 */
export function airfoil(t: number, thickness: number, camber = 0): [number, number] {
  const x = 0.5 + 0.5 * Math.cos(2 * Math.PI * t);
  const half = 5 * thickness * (0.2969 * Math.sqrt(x) - 0.126 * x - 0.3516 * x * x + 0.2843 * x ** 3 - 0.1036 * x ** 4);
  const p = 0.4;
  const mean =
    x < p ? (camber / (p * p)) * (2 * p * x - x * x) : (camber / (1 - p) ** 2) * (1 - 2 * p + 2 * p * x - x * x);
  return [x, t <= 0.5 ? mean + half : mean - half];
}

/** Smoothstep, for shaping profiles. */
export function smooth(edge0: number, edge1: number, x: number): number {
  const t = Math.min(Math.max((x - edge0) / (edge1 - edge0), 0), 1);
  return t * t * (3 - 2 * t);
}

/**
 * The point `t` (0 to 1) of the way round a rounded rectangle centred on the origin, counterclockwise from the middle
 * of its right side, as [across, up]. Points are spread evenly by length, so its corners stay round.
 */
export function roundedRect(t: number, halfWidth: number, halfHeight: number, radius: number): [number, number] {
  const straightX = 2 * (halfWidth - radius);
  const straightY = 2 * (halfHeight - radius);
  const arc = (Math.PI / 2) * radius;
  const perimeter = 2 * straightX + 2 * straightY + 4 * arc;
  // Runs: half the right side, then for each corner an arc and the next side, then the rest of the right side.
  const runs: [length: number, at: (f: number) => [number, number]][] = [
    [straightY / 2, (f) => [halfWidth, f * (straightY / 2)]],
    [arc, (f) => corner(halfWidth - radius, halfHeight - radius, f * 90)],
    [straightX, (f) => [halfWidth - radius - f * straightX, halfHeight]],
    [arc, (f) => corner(-(halfWidth - radius), halfHeight - radius, 90 + f * 90)],
    [straightY, (f) => [-halfWidth, halfHeight - radius - f * straightY]],
    [arc, (f) => corner(-(halfWidth - radius), -(halfHeight - radius), 180 + f * 90)],
    [straightX, (f) => [-(halfWidth - radius) + f * straightX, -halfHeight]],
    [arc, (f) => corner(halfWidth - radius, -(halfHeight - radius), 270 + f * 90)],
    [straightY / 2, (f) => [halfWidth, -(halfHeight - radius) + f * (straightY / 2)]],
  ];
  let left = (((t % 1) + 1) % 1) * perimeter;
  for (const [length, point] of runs) {
    if (length > 0 && left <= length) return point(left / length);
    left -= length;
  }
  return [halfWidth, 0];

  function corner(cx: number, cy: number, degrees: number): [number, number] {
    const angle = (degrees * Math.PI) / 180;
    return [cx + radius * Math.cos(angle), cy + radius * Math.sin(angle)];
  }
}

/**
 * A slab with rounded corners and softly rounded edges, like a laptop's body or a key: `width` across (x), `depth`
 * deep (z) and `height` tall (y), standing on y = 0, centred on x and z. Its coordinates for the shader are its own
 * position.
 */
export function slab(
  width: number,
  depth: number,
  height: number,
  corner: number,
  edge: number,
  part: number,
  detail: { around?: number; profile?: number } = {},
): BufferGeometry {
  const halfWidth = width / 2;
  const halfDepth = depth / 2;
  const inner = Math.min(halfWidth, halfDepth) - 0.0004;
  const rounding = Math.min(edge, height / 2);
  // Up the profile: the bottom face from the middle out, round the bottom edge, up the side, round the top edge,
  // and across the top face back to the middle.
  const profile = (v: number): [inset: number, y: number] => {
    if (v < 0.2) return [inner + (rounding - inner) * (v / 0.2), 0];
    if (v < 0.4) {
      const angle = -Math.PI / 2 + ((v - 0.2) / 0.2) * (Math.PI / 2);
      return [rounding - rounding * Math.cos(angle), rounding + rounding * Math.sin(angle)];
    }
    if (v < 0.6) return [0, rounding + ((v - 0.4) / 0.2) * (height - 2 * rounding)];
    if (v < 0.8) {
      const angle = ((v - 0.6) / 0.2) * (Math.PI / 2);
      return [rounding - rounding * Math.cos(angle), height - rounding + rounding * Math.sin(angle)];
    }
    return [rounding + (inner - rounding) * ((v - 0.8) / 0.2), height];
  };
  const profileSteps = detail.profile ?? 6;
  const vs = [
    ...steps(0, 0.2, 3),
    ...steps(0.2, 0.4, profileSteps).slice(1),
    ...steps(0.4, 0.6, 2).slice(1),
    ...steps(0.6, 0.8, profileSteps).slice(1),
    ...steps(0.8, 1, 3).slice(1),
  ];
  const geometry = grid(
    steps(0, 1, (detail.around ?? 64) + 1),
    vs,
    (t, v, out) => {
      const [inset, y] = profile(v);
      const [x, z] = roundedRect(t, halfWidth - inset, halfDepth - inset, Math.max(corner - inset, 0.0003));
      out.set(x, y, -z);
    },
    { part, params: () => [0, 0, 0] },
  );
  return geometry;
}

/** Turns a geometry's faces round if most of its normals point against `outward` (where each vertex should face). */
export function orient(geometry: BufferGeometry, outward: (position: Vector3) => Vector3): BufferGeometry {
  const positions = geometry.getAttribute("position");
  const normals = geometry.getAttribute("normal");
  let agreement = 0;
  for (let index = 0; index < positions.count; index += Math.max(1, Math.floor(positions.count / 64))) {
    at.fromBufferAttribute(positions, index);
    normal.fromBufferAttribute(normals, index);
    agreement += Math.sign(normal.dot(outward(at)));
  }
  if (agreement >= 0) return geometry;
  for (let index = 0; index < normals.count; index++) {
    normals.setXYZ(index, -normals.getX(index), -normals.getY(index), -normals.getZ(index));
  }
  const indices = geometry.getIndex();
  if (indices != null) {
    for (let index = 0; index < indices.count; index += 3) {
      const second = indices.getX(index + 1);
      indices.setX(index + 1, indices.getX(index + 2));
      indices.setX(index + 2, second);
    }
  }
  return geometry;
}

/**
 * Gives a geometry made elsewhere (a rounded box, an extrusion) what the flight's shaders read: its part, how light
 * or dark it is (`albedo`), how glossy, and its own coordinates (its local position, unless given).
 */
export function paint(
  geometry: BufferGeometry,
  part: number,
  albedo: number,
  gloss: number,
  params?: (position: Vector3) => readonly [number, number, number],
): BufferGeometry {
  const positions = geometry.getAttribute("position");
  const count = positions.count;
  const surface = new Float32Array(count * 3);
  for (let index = 0; index < count; index++) {
    at.fromBufferAttribute(positions, index);
    surface.set(params == null ? [at.x, at.y, at.z] : params(at), index * 3);
  }
  geometry.setAttribute("surface", new BufferAttribute(surface, 3));
  geometry.setAttribute("part", new BufferAttribute(new Float32Array(count).fill(part), 1));
  const finish = new Float32Array(count * 2);
  for (let index = 0; index < count; index++) finish.set([albedo, gloss], index * 2);
  geometry.setAttribute("finish", new BufferAttribute(finish, 2));
  geometry.deleteAttribute("uv");
  return geometry;
}
