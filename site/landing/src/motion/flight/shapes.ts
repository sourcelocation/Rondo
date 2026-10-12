import { BufferAttribute, BufferGeometry, Vector3 } from "three";

/**
 * Building blocks for the few shapes the flight makes in code (the open window's glass, the sun's shafts): a smooth
 * surface given by a function of two parameters, sampled as a grid. Normals come from the function itself, so seams
 * and tips stay smooth. Each vertex also carries the surface's own coordinates (`surface`) and which part it belongs
 * to (`part`), as the models' do (model.ts).
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
