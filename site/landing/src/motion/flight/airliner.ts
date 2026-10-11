import {
  AdditiveBlending,
  BufferAttribute,
  type BufferGeometry,
  DoubleSide,
  Group,
  InstancedBufferAttribute,
  InstancedBufferGeometry,
  Mesh,
  MeshBasicMaterial,
  ShaderMaterial,
  type Texture,
  Vector3,
  type IUniform,
  type Matrix4,
} from "three";
import { mergeGeometries } from "three/addons/utils/BufferGeometryUtils.js";
import { type CloudMaps, cloudField } from "./clouds";
import { common, shadows, shapes, shared } from "./shading";
import { airfoil, bunched, grid, smooth, spline, steps, type Surface } from "./shapes";

/**
 * The airliner: a modern twin-engined widebody in white paint, flying toward -z with its left side (-x) to the
 * camera. It's made in code, part by part (motion/flight/shapes.ts): a fuselage with a drooped nose and an upswept
 * tail, wings swept and bent up in flight with raked tips, engines with serrated nozzles, the tail, the fairings and
 * antennas. Windows, doors, the cockpit's glass, panel lines and the wings' moving surfaces are drawn into the paint
 * by its shader, crisp at any distance. One window, in business class on the left, is open: the camera goes in by it.
 * The plane holds still and the world moves past it, so its shadows are worked out once.
 */

/** The fuselage's radius, and where its nose and tail end, in metres along z. */
const RADIUS = 2.9;
const NOSE = -28;
const TAIL = 28.4;
/** Where the round barrel of the cabin begins and ends. */
const FRONT = -20.4;
const BACK = 7.8;

/** The passenger windows: their size, the height of their middles, how far apart, and the rows between doors. */
const WINDOW = { y: 0.15, halfWidth: 0.135, halfHeight: 0.235, radius: 0.12, pitch: 0.6 } as const;
const ROWS: readonly (readonly [first: number, count: number])[] = [
  [-19.6, 16],
  [-7.9, 27],
  [10.5, 13],
  [20.4, 2],
];
const DOORS = [-20.9, -9.2, 9.2, 19.2] as const;

/** Where things are on the plane, for the cabin, the clouds and the camera to find. */
export const layout = {
  radius: RADIUS,
  nose: NOSE,
  tail: TAIL,
  /** Where the fuselage is a plain round barrel, along z. */
  barrel: { front: FRONT, back: BACK },
  window: WINDOW,
  /** The open window: business class, the left side. */
  hero: new Vector3(-Math.sqrt(RADIUS ** 2 - WINDOW.y ** 2), WINDOW.y, -14.8),
  /** The wingtips, where the strobes are and the vortices start. */
  tip: new Vector3(-29.55, 3.06, 13.9),
  engine: new Vector3(-9.6, -2.85, -6.3),
} as const;

/** Each part's number, which its shader draws it by. */
const PART = { skin: 0, wing: 1, tail: 2, cowl: 3, dark: 4, metal: 5, fairing: 6 } as const;

/** The fuselage's cross-section at z: an ellipse, its middle's height and its half-width and half-height. */
function section(z: number): { centre: number; width: number; height: number } {
  if (z < FRONT) {
    const t = Math.max((z - NOSE) / (FRONT - NOSE), 0);
    const r = RADIUS * (1 - (1 - t) ** 2.1) ** (1 / 2.1);
    return { centre: -0.95 * (1 - t) ** 2.2, width: r, height: r };
  }
  if (z > BACK) {
    const t = Math.min((z - BACK) / (TAIL - BACK), 1);
    const top = RADIUS - (RADIUS - 1.75) * smooth(0.22, 1, t) ** 1.35;
    const bottom = -RADIUS + (RADIUS + 1.05) * t ** 1.7;
    return { centre: (top + bottom) / 2, width: RADIUS - (RADIUS - 0.3) * t ** 1.45, height: (top - bottom) / 2 };
  }
  return { centre: 0, width: RADIUS, height: RADIUS };
}

function fuselage(): BufferGeometry {
  const zs = [
    ...Array.from({ length: 46 }, (_, index) => NOSE + (FRONT - NOSE) * (index / 45) ** 1.9),
    ...steps(FRONT, BACK, 34).slice(1),
    ...steps(BACK, TAIL, 46).slice(1),
  ];
  const around = steps(-Math.PI, Math.PI, 129);
  const body: Surface = (z, angle, out) => {
    const { centre, width, height } = section(z);
    out.set(width * Math.sin(angle), centre + height * Math.cos(angle), z);
  };
  const geometry = grid(zs, around, body, {
    part: PART.skin,
    params: (z, angle) => [z, angle, section(z).width],
  });
  // The very tip of the nose is a point; it faces ahead, and a little down, as the nose droops.
  const normals = geometry.getAttribute("normal");
  const tip = new Vector3(0, -0.3, -1).normalize();
  for (let index = 0; index < around.length; index++) normals.setXYZ(index, tip.x, tip.y, tip.z);
  return geometry;
}

/** The tail cone's end: the auxiliary power unit's exhaust, dark. */
function tailEnd(): BufferGeometry {
  const { centre, width, height } = section(TAIL);
  return grid(
    [0, 1],
    steps(-Math.PI, Math.PI, 33),
    (r, angle, out) =>
      out.set(width * r * 0.92 * Math.sin(angle), centre + height * r * 0.92 * Math.cos(angle), TAIL + 0.02 - r * 0.02),
    { part: PART.dark, flip: true },
  );
}

/** A lifting surface's station: where its leading edge is, how long its chord, how thick and how twisted. */
interface Station {
  readonly x: number;
  readonly y: number;
  readonly lead: number;
  readonly chord: number;
  readonly thickness: number;
  readonly twist: number;
}

/** Linear interpolation through `points`, by x. */
function through(points: readonly (readonly [number, number])[], x: number): number {
  const first = points[0];
  if (first == null) return 0;
  if (x <= first[0]) return first[1];
  for (let index = 1; index < points.length; index++) {
    const [x1, y1] = points[index] ?? first;
    const [x0, y0] = points[index - 1] ?? first;
    if (x <= x1) return y0 + ((y1 - y0) * (x - x0)) / (x1 - x0);
  }
  return points[points.length - 1]?.[1] ?? 0;
}

/** The main wing, out from the fuselage's middle: swept, kinked, bent up in flight, its tip raked further back. */
const WING_ROOT = 2.2;
const WING_TIP = 29.6;

function wingStation(x: number): Station {
  const out = x - WING_ROOT;
  const lead = x <= 27 ? -5.7 + out * 0.637 : -5.7 + 24.8 * 0.637 + (x - 27) * 1.28;
  return {
    x,
    y: -1.62 + out * 0.105 + 0.0024 * out * out,
    lead,
    chord: through(
      [
        [WING_ROOT, 10.6],
        [9.6, 5.95],
        [27, 1.95],
        [WING_TIP, 0.6],
      ],
      x,
    ),
    thickness: through(
      [
        [WING_ROOT, 0.14],
        [9.6, 0.105],
        [27, 0.09],
        [WING_TIP, 0.06],
      ],
      x,
    ),
    twist:
      (through(
        [
          [WING_ROOT, 2.5],
          [27, -1.5],
          [WING_TIP, -2],
        ],
        x,
      ) *
        Math.PI) /
      180,
  };
}

/** Where a station's airfoil puts the point `t` of the way around it, horizontal (a wing or a tailplane). */
function onAirfoil(station: Station, t: number, camber: number, side: number, out: Vector3): void {
  const [along, up] = airfoil(t, station.thickness, camber);
  // Twisted about the quarter chord: a positive twist lifts the leading edge.
  const ahead = (along - 0.25) * station.chord;
  const lift = up * station.chord;
  const cos = Math.cos(station.twist);
  const sin = Math.sin(station.twist);
  out.set(
    side * station.x,
    station.y + lift * cos - ahead * sin,
    station.lead + 0.25 * station.chord + ahead * cos + lift * sin,
  );
}

/** A wing on one side (-1 left, 1 right): a grid of airfoils along its span, closed at its tip. */
function lifting(
  stationAt: (x: number) => Station,
  spans: readonly number[],
  side: number,
  part: number,
  camber: number,
): BufferGeometry {
  const around = steps(0, 1, 57);
  const root = spans[0] ?? 0;
  const last = spans[spans.length - 1] ?? 1;
  return grid(
    spans,
    around,
    (x, t, out) => {
      // The last station closes the tip: the airfoil flattens to its mean line.
      const station = stationAt(x);
      const closing = x >= last ? { ...station, thickness: 0.0005 } : station;
      onAirfoil(closing, t, camber, side, out);
    },
    {
      part,
      // Right-handed on the right, so the left's mirror turns its faces back out.
      flip: side < 0,
      params: (x, t) => {
        const station = stationAt(x);
        const [along] = airfoil(t, station.thickness);
        return [x - root, along * station.chord, (t <= 0.5 ? 1 : -1) * station.chord];
      },
    },
  );
}

function wings(): BufferGeometry[] {
  const spans = [...bunched(WING_ROOT, 27, 40, 0.35), ...bunched(27, WING_TIP, 12, 0.6).slice(1)];
  return [lifting(wingStation, spans, -1, PART.wing, 0.018), lifting(wingStation, spans, 1, PART.wing, 0.018)];
}

/** The tailplanes: swept back and tipped up, with no camber. */
function tailplaneStation(x: number): Station {
  const out = x - 0.9;
  return {
    x,
    y: 0.95 + out * 0.123,
    lead: 19.2 + out * 0.7,
    chord: 5.4 - out * 0.37,
    thickness: 0.11 - out * 0.0021,
    twist: 0,
  };
}

function tailplanes(): BufferGeometry[] {
  const spans = bunched(0.9, 10.4, 26, 0.4);
  return [lifting(tailplaneStation, spans, -1, PART.tail, 0), lifting(tailplaneStation, spans, 1, PART.tail, 0)];
}

/** The fin: an upright airfoil swept back, its sections stacked from inside the tail cone to its top. */
function fin(): BufferGeometry {
  const station = (height: number) => {
    const out = height - 1.8;
    return { lead: 15.4 + out * 0.84, chord: 8.2 - out * 0.55, thickness: 0.11 - out * 0.0021 };
  };
  const heights = bunched(1.8, 11.6, 30, 0.4);
  const top = heights[heights.length - 1] ?? 11.6;
  return grid(
    heights,
    steps(0, 1, 57),
    (height, t, out) => {
      const { lead, chord, thickness } = station(height);
      const [along, side] = airfoil(t, height >= top ? 0.0005 : thickness);
      out.set(side * chord, height, lead + along * chord);
    },
    {
      part: PART.tail,
      flip: true,
      params: (height, t) => {
        const { chord, thickness } = station(height);
        const [along] = airfoil(t, thickness);
        return [height - 1.8, along * chord, (t <= 0.5 ? 1 : -1) * chord];
      },
    },
  );
}

/** A body of revolution around an axis along z at (x, y): its profile is a curve of [radius, z] points. */
function lathe(
  profile: readonly (readonly [number, number])[],
  centre: { x: number; y: number; z: number },
  part: number,
  options: { inside?: boolean; samples?: number; around?: number; teeth?: (angle: number) => number } = {},
): BufferGeometry {
  const curve = spline(profile);
  // Faces turn out from the axis (or in, for a surface seen from inside), whichever way the profile runs.
  const backward = curve(0.55)[1] > curve(0.45)[1];
  const samples = options.samples ?? Math.max(profile.length * 4, 8);
  const along = steps(0, 1, samples);
  const angles = steps(0, Math.PI * 2, (options.around ?? 72) + 1);
  const at: Surface = (u, angle, out) => {
    const [radius, z] = curve(u);
    // Teeth cut into the last ring (a serrated nozzle), fading in over the last stretch of the profile.
    const cut = options.teeth == null ? 0 : options.teeth(angle) * smooth(0.82, 1, u);
    out.set(centre.x + radius * Math.cos(angle), centre.y + radius * Math.sin(angle), centre.z + z - cut);
  };
  return grid(along, angles, at, {
    part,
    flip: backward !== (options.inside === true),
    params: (u, angle) => {
      const [radius, z] = curve(u);
      return [z, angle, radius];
    },
  });
}

/** One engine and its pylon, on one side: the nacelle, its intake and fan, its nozzles and the exhaust cone. */
function engine(side: number): BufferGeometry[] {
  const centre = { x: side * -layout.engine.x, y: layout.engine.y, z: layout.engine.z };
  // Fourteen chevrons around the fan nozzle's edge.
  const teeth = (angle: number) => {
    const k = (angle / (Math.PI * 2)) * 14;
    return 0.26 * (1 - Math.abs(2 * (k - Math.floor(k)) - 1));
  };
  const cowl = lathe(
    [
      [1.38, 0],
      [1.48, 0.07],
      [1.57, 0.24],
      [1.64, 0.58],
      [1.69, 1.15],
      [1.705, 1.95],
      [1.69, 2.8],
      [1.63, 3.6],
      [1.53, 4.3],
      [1.44, 4.85],
    ],
    centre,
    PART.cowl,
    { samples: 40, around: 112, teeth },
  );
  // Run front to back, so its teeth are cut at the nozzle, with the cowl's.
  const duct = lathe(
    [
      [1.25, 1.0],
      [1.27, 2.2],
      [1.31, 3.2],
      [1.37, 4.2],
      [1.415, 4.85],
    ],
    centre,
    PART.dark,
    { samples: 16, around: 112, teeth, inside: true },
  );
  const intake = lathe(
    [
      [1.38, 0],
      [1.315, 0.08],
      [1.275, 0.32],
      [1.255, 0.9],
    ],
    centre,
    PART.cowl,
    { samples: 12, around: 112, inside: true },
  );
  const fan = lathe(
    [
      [1.26, 0.95],
      [0.9, 0.97],
      [0.42, 0.75],
      [0.0, 0.42],
    ],
    centre,
    PART.dark,
    { samples: 12 },
  );
  const core = lathe(
    [
      [1.06, 3.9],
      [1.05, 4.6],
      [1.0, 5.3],
      [0.9, 6.0],
      [0.8, 6.55],
    ],
    centre,
    PART.metal,
    { samples: 20 },
  );
  const coreNozzle = lathe(
    [
      [0.78, 6.55],
      [0.72, 6.3],
      [0.66, 6.1],
    ],
    centre,
    PART.dark,
    { samples: 6, inside: true },
  );
  const plug = lathe(
    [
      [0.66, 6.1],
      [0.6, 6.85],
      [0.48, 7.4],
      [0.31, 7.85],
      [0.12, 8.18],
      [0.0, 8.3],
    ],
    centre,
    PART.metal,
    { samples: 22 },
  );
  return [cowl, duct, intake, fan, core, coreNozzle, plug, pylon(side)];
}

/** The pylon: a blade from the nacelle's top up into the wing, its leading edge climbing to the wing's. */
function pylon(side: number): BufferGeometry {
  const x = side * -layout.engine.x;
  return grid(
    steps(0, 1, 8),
    steps(0, 1, 33),
    (h, t, out) => {
      const front = -3.5 + 2.6 * h ** 0.8;
      const back = 2.3 - 0.6 * h;
      const chord = back - front;
      const [along, thick] = airfoil(t, 0.42 / chord);
      out.set(x + thick * chord, -1.36 + h * 0.8, front + along * chord);
    },
    { part: PART.fairing, flip: true },
  );
}

/** The wing-to-body fairing: a long, rounded blister under the fuselage, where the wings go through it. */
function belly(): BufferGeometry {
  const half = { x: 2.62, y: 0.95, z: 10.2 };
  const centre = { y: -2.42, z: 0.1 };
  return grid(
    steps(-1, 1, 40),
    steps(-Math.PI, Math.PI, 65),
    (u, angle, out) => {
      const scale = (1 - Math.abs(u) ** 2.6) ** (1 / 2.6);
      const sin = Math.sin(angle);
      const cos = Math.cos(angle);
      out.set(
        half.x * scale * Math.sign(sin) * Math.abs(sin) ** 0.8,
        centre.y + half.y * scale * Math.sign(cos) * Math.abs(cos) ** 0.8,
        centre.z + half.z * u,
      );
    },
    { part: PART.fairing },
  );
}

/** The flap tracks' fairings: slim canoes hanging under each wing, rounded in front and pointed behind it. */
function canoes(side: number): BufferGeometry[] {
  return [5.8, 12.6, 17.8].map((x) => {
    const station = wingStation(x);
    // The wing's underside about two thirds of the way back, which the canoe hangs from.
    const underside = new Vector3();
    onAirfoil(station, 0.79, 0.018, side, underside);
    const start = station.lead + 0.52 * station.chord;
    const length = 0.48 * station.chord + 1.7;
    const hang = 0.34 + 0.012 * station.chord;
    return grid(
      steps(0, 1, 30),
      steps(0, Math.PI * 2, 29),
      (u, angle, out) => {
        const fullness = Math.sin((Math.PI / 2) * Math.min(u / 0.22, 1)) ** 0.7 * (1 - smooth(0.5, 1, u)) ** 0.85;
        const halfHeight = (hang / 2) * fullness + 0.05;
        const centre = underside.y + 0.06 - (hang / 2) * fullness;
        out.set(
          side * x + 0.17 * fullness * Math.cos(angle),
          centre + halfHeight * Math.sin(angle),
          start + length * u,
        );
      },
      { part: PART.fairing, flip: true },
    );
  });
}

/** The blade antennas on the crown and the belly. */
function antennas(): BufferGeometry[] {
  return [
    { z: -12.5, up: 1 },
    { z: 1.8, up: 1 },
    { z: -6.2, up: -1 },
  ].map(({ z, up }) =>
    grid(
      steps(0, 1, 6),
      steps(0, 1, 17),
      (h, t, out) => {
        const chord = 0.5 - 0.24 * h;
        const [along, thick] = airfoil(t, 0.12);
        out.set(thick * chord, up * (RADIUS - 0.05 + h * 0.36), z + h * 0.32 + along * chord);
      },
      { part: PART.fairing, flip: up > 0 },
    ),
  );
}

/** Every part, merged into one geometry: the plane is a single draw. */
function airframe(): BufferGeometry {
  const parts = [
    fuselage(),
    tailEnd(),
    ...wings(),
    ...tailplanes(),
    fin(),
    ...engine(-1),
    ...engine(1),
    belly(),
    ...canoes(-1),
    ...canoes(1),
    ...antennas(),
  ];
  const merged = mergeGeometries(parts);
  for (const part of parts) part.dispose();
  return merged;
}

const vertex = /* glsl */ `
  attribute vec3 surface;
  attribute float part;
  varying vec3 vWorld;
  varying vec3 vNormal;
  varying vec3 vSurface;
  varying float vPart;

  void main() {
    vec4 world = modelMatrix * vec4(position, 1.0);
    vWorld = world.xyz;
    vNormal = normalize(mat3(modelMatrix) * normal);
    vSurface = surface;
    vPart = part;
    gl_Position = projectionMatrix * viewMatrix * world;
  }
`;

/** Where the passenger windows and doors are, for the paint and for the open window's glass. */
const openings = /* glsl */ `
  const float HERO_Z = ${layout.hero.z.toFixed(3)};
  const vec2 WINDOW_HALF = vec2(${WINDOW.halfWidth}, ${WINDOW.halfHeight});
  const float WINDOW_Y = ${WINDOW.y};
  const float WINDOW_RADIUS = ${WINDOW.radius};
  const float PITCH = ${WINDOW.pitch};
  const vec2 ROWS[${ROWS.length}] = vec2[${ROWS.length}](${ROWS.map(([first, count]) => `vec2(${first.toFixed(2)}, ${count.toFixed(1)})`).join(", ")});
  const float DOORS[${DOORS.length}] = float[${DOORS.length}](${DOORS.map((z) => z.toFixed(2)).join(", ")});

  // How far a point on the fuselage's side is from the nearest passenger window (inside, below zero).
  float windowAt(vec3 p) {
    if (abs(p.x) < 2.2) return 10.0;
    float nearest = 10.0;
    for (int i = 0; i < ${ROWS.length}; i++) {
      vec2 row = ROWS[i];
      float k = clamp(floor((p.z - row.x) / PITCH + 0.5), 0.0, row.y - 1.0);
      nearest = min(nearest, roundedBox(vec2(p.z - row.x - k * PITCH, p.y - WINDOW_Y), WINDOW_HALF, WINDOW_RADIUS));
    }
    return nearest;
  }

  // Whether a point is in the open window the camera goes in by.
  bool inHero(vec3 p) {
    return p.x < 0.0 && abs(p.z - HERO_Z) < 0.3 && roundedBox(vec2(p.z - HERO_Z, p.y - WINDOW_Y), WINDOW_HALF, WINDOW_RADIUS) < 0.0;
  }
`;

/** Glass, as the windows and the cockpit have it: nearly black, mirroring the sky and the clouds at an angle. */
const glass = /* glsl */ `
  float glassAt(vec3 n, vec3 view) {
    float facing = max(dot(n, view), 0.0);
    float fresnel = 0.05 + 0.95 * pow(1.0 - facing, 5.0);
    return 0.02 + fresnel * environment(reflect(-view, n)) * 0.9;
  }
`;

const fragment = /* glsl */ `
  ${common}
  ${shadows}
  ${shapes}
  ${openings}
  ${glass}
  ${cloudField}

  uniform sampler2DShadow uShadow;
  uniform mat4 uShadowMatrix;
  uniform float uShadowTexel;
  varying vec3 vWorld;
  varying vec3 vNormal;
  varying vec3 vSurface;
  varying float vPart;

  // A convex quad's signed distance (inside, below zero), its corners counterclockwise.
  float edge(vec2 p, vec2 a, vec2 b) {
    vec2 e = b - a;
    return (e.y * (p.x - a.x) - e.x * (p.y - a.y)) / length(e);
  }

  float quad(vec2 p, vec2 a, vec2 b, vec2 c, vec2 d) {
    return max(max(edge(p, a, b), edge(p, b, c)), max(edge(p, c, d), edge(p, d, a)));
  }

  // The cockpit's four panes, on (z, distance around from the crown).
  float cockpitAt(vec2 p) {
    float front = quad(p, vec2(-26.3, 0.07), vec2(-25.47, 0.07), vec2(-25.27, 0.98), vec2(-26.05, 0.9)) - 0.035;
    float side = quad(p, vec2(-25.9, 1.1), vec2(-24.78, 1.13), vec2(-24.3, 1.92), vec2(-25.55, 1.84)) - 0.035;
    return min(front, side);
  }

  const float SEAMS[7] = float[7](-22.6, -14.2, -4.6, 3.2, 11.8, 17.4, 22.4);
  const float SPOILERS[9] = float[9](1.4, 3.0, 4.6, 6.4, 8.2, 11.0, 13.4, 16.0, 19.2);
  const float FLAPS[4] = float[4](6.9, 8.6, 18.4, 24.6);
  const float SLATS[6] = float[6](10.4, 13.2, 16.0, 18.8, 21.6, 24.4);

  // Panel lines and rivets on the fuselage: thin seams around it and along it, rows of rivets beside them.
  float seamsAt(vec3 p, float around) {
    float lines = 0.0;
    for (int i = 0; i < 7; i++) lines = max(lines, stroke(p.z - SEAMS[i], 0.004));
    float a = abs(around);
    lines = max(lines, stroke(a - 1.32, 0.004));
    lines = max(lines, stroke(a - 3.15, 0.004));
    lines = max(lines, stroke(a - 5.55, 0.004));
    // Rivets every 12 cm beside the seams along the fuselage, which only show up close.
    float rivet = length(vec2(fract(p.z / 0.12) - 0.5, (a - 3.15 - 0.03) / 0.12) * 0.12);
    rivet = min(rivet, length(vec2(fract(p.z / 0.12) - 0.5, (a - 1.32 - 0.03) / 0.12) * 0.12));
    float dots = (1.0 - smoothstep(0.0045, 0.0045 + fwidth(rivet), rivet)) * (1.0 - smoothstep(0.0012, 0.0026, fwidth(p.z)));
    return max(lines, dots * 0.6);
  }

  void main() {
    int part = int(vPart + 0.5);
    if (!gl_FrontFacing) {
      // Inside the fuselage, past the cabin: the cabin's white, open only where the cabin's own windows are.
      if (part != 0 || (vWorld.x < 0.0 && windowAt(vWorld) < 0.0) || inHero(vWorld)) discard;
      gl_FragColor = vec4(1.0);
      return;
    }
    vec3 n = normalize(vNormal);
    vec3 view = normalize(cameraPosition - vWorld);

    float albedo = 0.9;
    float gloss = 0.5;
    float seams = 0.0;
    float isGlass = 0.0;

    if (part == 0) {
      // The fuselage: windows, doors, the cockpit, seams.
      if (inHero(vWorld)) discard;
      float around = vSurface.y * vSurface.z;
      float pane = windowAt(vWorld);
      isGlass = 1.0 - smoothstep(-fwidth(pane), fwidth(pane), pane);
      float rim = stroke(pane - 0.012, 0.01);
      seams = max(seams, rim * 0.55);
      for (int i = 0; i < ${DOORS.length}; i++) {
        vec2 q = vec2(vWorld.z - DOORS[i], vWorld.y + 0.02);
        if (abs(q.x) > 0.8 || abs(vWorld.x) < 1.6) continue;
        float door = roundedBox(q, vec2(0.53, 0.94), 0.2);
        seams = max(seams, stroke(door, 0.006));
        float porthole = roundedBox(q - vec2(0.0, WINDOW_Y + 0.15), vec2(0.1, 0.15), 0.09);
        isGlass = max(isGlass, 1.0 - smoothstep(-fwidth(porthole), fwidth(porthole), porthole));
        seams = max(seams, stroke(roundedBox(q - vec2(0.0, -0.28), vec2(0.12, 0.012), 0.012), 0.004));
      }
      if (vWorld.z < -23.0) {
        float cockpit = cockpitAt(vec2(vWorld.z, abs(around)));
        isGlass = max(isGlass, 1.0 - smoothstep(-fwidth(cockpit), fwidth(cockpit), cockpit));
        seams = max(seams, stroke(cockpit - 0.02, 0.012) * 0.6);
      }
      seams = max(seams, seamsAt(vWorld, around) * 0.7);
    } else if (part == 1 || part == 2) {
      // The wings and the tail: their moving surfaces' hinges and gaps, and a bare metal leading edge.
      float span = vSurface.x;
      float along = vSurface.y;
      float chord = abs(vSurface.z);
      bool upper = vSurface.z > 0.0;
      float c = along / chord;
      if (part == 1) {
        float tipward = span / 27.4;
        if (tipward < 0.93) seams = max(seams, stroke(along - 0.8 * chord, 0.007));
        if (upper) {
          if (tipward > 0.04 && tipward < 0.75) seams = max(seams, stroke(along - 0.64 * chord, 0.006) * step(0.6, c));
          if (c > 0.62 && c < 0.995) {
            for (int i = 0; i < 9; i++) {
              float at = SPOILERS[i];
              seams = max(seams, stroke(span - at, 0.006) * step(c, 0.8 + 0.2 * step(16.5, at)));
            }
            for (int i = 0; i < 4; i++) {
              float at = FLAPS[i];
              seams = max(seams, stroke(span - at, 0.006) * step(0.8, c));
            }
          }
          if (span > 8.2 && c < 0.16) {
            seams = max(seams, stroke(along - 0.13 * chord, 0.006));
            for (int i = 0; i < 6; i++) {
              float at = SLATS[i];
              seams = max(seams, stroke(span - at, 0.006) * step(c, 0.13));
            }
          }
        }
      } else {
        seams = max(seams, stroke(along - 0.7 * chord, 0.006));
        seams = max(seams, stroke(span - 4.6, 0.006) * step(0.7, c));
      }
      float lead = 1.0 - smoothstep(0.025, 0.04, c);
      albedo = mix(albedo, 0.62, lead);
      gloss = mix(gloss, 0.95, lead);
    } else if (part == 3) {
      // The nacelle: a bare metal intake lip, the cowls' joints, metal toward the serrated nozzle.
      float z = vSurface.x;
      float lip = 1.0 - smoothstep(0.16, 0.2, z);
      albedo = mix(albedo, 0.6, lip);
      gloss = mix(gloss, 0.95, lip);
      seams = max(seams, stroke(z - 0.55, 0.006));
      seams = max(seams, stroke(z - 2.62, 0.006));
      seams = max(seams, stroke(z - 4.2, 0.006));
      seams = max(seams, stroke(sin(vSurface.y + 1.5708) * vSurface.z, 0.006) * step(0.0, -sin(vSurface.y)));
      float nozzle = smoothstep(4.25, 4.35, z);
      albedo = mix(albedo, 0.7, nozzle);
    } else if (part == 4) {
      albedo = 0.04;
      gloss = 0.25;
    } else if (part == 5) {
      // The exhaust's metal: grey, and darker inside the bypass duct, where little light gets.
      albedo = mix(0.08, 0.5, smoothstep(4.6, 5.1, vSurface.x));
      gloss = 0.85;
    }

    float sun = SUN * wrapped(dot(n, uSun), 0.1) * shadowFrom(uShadow, uShadowMatrix, uShadowTexel, vWorld + n * (0.03 + 0.12 * (1.0 - clamp(dot(n, uSun), 0.0, 1.0))));
    float paint = albedo * (1.0 - seams * 0.5) * (sun + ambient(n));
    float facing = max(dot(n, view), 0.0);
    float fresnel = 0.04 + 0.96 * pow(1.0 - facing, 5.0);
    vec3 halfway = normalize(uSun + view);
    float glint = pow(max(dot(n, halfway), 0.0), 140.0) * 6.0 * step(0.0, dot(n, uSun)) * min(sun, 1.0);
    float radiance = paint + gloss * (fresnel * environment(reflect(-view, n)) * 0.55 + glint * 0.5);
    radiance = mix(radiance, glassAt(n, view) + glint * 0.6, isGlass);

    // Where the plane dips into the clouds' tops (its engines, its belly), they veil it: a few steps from its
    // surface back toward the eye, through whatever cloud lies between.
    if (vWorld.y < -2.0) {
      float veil = 1.0;
      for (int i = 1; i <= 5; i++) {
        vec3 p = vWorld + view * float(i) * 1.1;
        veil *= exp(-cloudAt(p, topAt(p.xz), 1.0) * 0.42 * 1.1);
      }
      radiance = mix(2.4, radiance, veil);
    }
    float distance = length(cameraPosition - vWorld);
    radiance = mix(radiance, WHITE, haze(distance));
    gl_FragColor = finish(vec3(radiance));
  }
`;

/** The fuselage's shadow inside, with its windows open: what lets the sun into the cabin in patches. */
const wallsFragment = /* glsl */ `
  ${shapes}
  ${openings}
  varying vec3 vWorld;
  varying float vPart;

  void main() {
    if (int(vPart + 0.5) != 0 || windowAt(vWorld) < 0.0 || inHero(vWorld)) discard;
    gl_FragColor = vec4(0.0);
  }
`;

/** The open window's glass: drawn over the cabin, it clears as the camera comes to it. */
const glassFragment = /* glsl */ `
  ${common}
  ${shapes}
  ${openings}
  ${glass}
  uniform float uClear;
  varying vec3 vWorld;
  varying vec3 vNormal;

  void main() {
    float pane = roundedBox(vec2(vWorld.z - HERO_Z, vWorld.y - WINDOW_Y), WINDOW_HALF, WINDOW_RADIUS);
    float inside = 1.0 - smoothstep(-fwidth(pane), fwidth(pane), pane);
    if (inside <= 0.0) discard;
    vec3 n = normalize(vNormal);
    vec3 view = normalize(cameraPosition - vWorld);
    float radiance = mix(glassAt(n, view), WHITE, haze(length(cameraPosition - vWorld)));
    gl_FragColor = vec4(finish(vec3(radiance)).rgb, inside * (1.0 - uClear));
  }
`;

/** The strobes and beacons: white flashes with a glow, drawn over everything, as additive light. */
const lightVertex = /* glsl */ `
  attribute vec4 light;
  uniform float uTime;
  varying vec2 vCorner;
  varying float vFlash;

  float pulse(float t, float at) {
    float d = (t - at) / 0.035;
    return exp(-d * d);
  }

  void main() {
    // Strobes double-flash every 1.4 s; beacons flash once a second, top and bottom in turn; the tail light stays on.
    float kind = light.w;
    float t = mod(uTime, 1.4);
    float flash = kind < 0.5
      ? pulse(t, 0.0) + pulse(t, 0.13)
      : kind < 1.5 ? pulse(mod(uTime + 0.5 * step(0.0, -light.y), 1.0), 0.06) * 0.8 : 0.22;
    vFlash = flash;
    vCorner = position.xy;
    vec4 centre = viewMatrix * vec4(light.xyz, 1.0);
    float size = 0.5 + 1.6 * flash;
    gl_Position = projectionMatrix * (centre + vec4(position.xy * size, 0.0, 0.0));
  }
`;

const lightFragment = /* glsl */ `
  varying vec2 vCorner;
  varying float vFlash;

  void main() {
    float r = length(vCorner);
    float glow = exp(-r * r * 9.0) * 0.8 + exp(-r * r * 90.0);
    gl_FragColor = vec4(vec3(glow * vFlash), 1.0);
  }
`;

/** The shadow maps the plane is lit with, and the uniforms its shaders read them from. */
export interface Shadowing {
  readonly uShadow: IUniform<Texture | null>;
  readonly uShadowMatrix: IUniform<Matrix4>;
  readonly uShadowTexel: IUniform<number>;
}

export class Airliner {
  /** What the camera sees. */
  readonly group = new Group();
  /** What casts the plane's shadows: the same shapes, depth only. */
  readonly casters = new Group();
  /** What shades the cabin: the fuselage alone, its windows open. */
  readonly walls = new Group();
  private readonly glass: ShaderMaterial;

  constructor(shadowing: Shadowing, clouds: CloudMaps) {
    const geometry = airframe();
    const body = new Mesh(
      geometry,
      new ShaderMaterial({
        vertexShader: vertex,
        fragmentShader: fragment,
        uniforms: { ...shared, ...shadowing, ...clouds },
        // Its inside shows past the cabin, as white.
        side: DoubleSide,
      }),
    );
    body.renderOrder = 1;
    this.group.add(body);

    this.casters.add(new Mesh(geometry, new MeshBasicMaterial({ side: DoubleSide, colorWrite: false })));
    this.walls.add(
      new Mesh(
        geometry,
        new ShaderMaterial({
          vertexShader: vertex,
          fragmentShader: wallsFragment,
          side: DoubleSide,
          colorWrite: false,
        }),
      ),
    );

    this.glass = new ShaderMaterial({
      vertexShader: vertex,
      fragmentShader: glassFragment,
      uniforms: { ...shared, uClear: { value: 0 } },
      transparent: true,
      depthWrite: false,
    });
    const { hero } = layout;
    const pane = grid(
      steps(hero.z - 0.16, hero.z + 0.16, 6),
      steps(WINDOW.y - 0.26, WINDOW.y + 0.26, 10),
      (z, y, out) => out.set(-Math.sqrt(RADIUS ** 2 - y * y) - 0.002, y, z),
      { part: PART.skin },
    );
    const glassMesh = new Mesh(pane, this.glass);
    glassMesh.renderOrder = 4;
    this.group.add(glassMesh);
    this.group.add(this.lights());
  }

  /** How clear the open window's glass is, from 0 (as dark as the others) to 1 (gone). */
  set clear(value: number) {
    const uniform = this.glass.uniforms.uClear;
    if (uniform != null) uniform.value = value;
  }

  private lights(): Mesh {
    const tip = layout.tip;
    const lights = [
      [tip.x, tip.y, tip.z, 0],
      [-tip.x, tip.y, tip.z, 0],
      [0, RADIUS + 0.06, -1.0, 1],
      [0, -3.4, 0.6, 1],
      [0, section(TAIL).centre, TAIL + 0.08, 2],
    ];
    const geometry = new InstancedBufferGeometry();
    geometry.setAttribute(
      "position",
      new BufferAttribute(new Float32Array([-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0]), 3),
    );
    geometry.setIndex([0, 1, 2, 0, 2, 3]);
    geometry.setAttribute("light", new InstancedBufferAttribute(new Float32Array(lights.flat()), 4));
    geometry.instanceCount = lights.length;
    const mesh = new Mesh(
      geometry,
      new ShaderMaterial({
        vertexShader: lightVertex,
        fragmentShader: lightFragment,
        uniforms: shared,
        transparent: true,
        depthWrite: false,
        blending: AdditiveBlending,
      }),
    );
    mesh.frustumCulled = false;
    mesh.renderOrder = 6;
    return mesh;
  }
}
