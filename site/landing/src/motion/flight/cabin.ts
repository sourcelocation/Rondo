import {
  AdditiveBlending,
  type BufferGeometry,
  CatmullRomCurve3,
  DoubleSide,
  Group,
  Matrix4,
  Mesh,
  Quaternion,
  ShaderMaterial,
  TubeGeometry,
  Vector3,
  Vector4,
  type IUniform,
  type Texture,
} from "three";
import { mergeGeometries } from "three/addons/utils/BufferGeometryUtils.js";
import { layout } from "./airliner";
import { Laptop, LAPTOP_PART } from "./laptop";
import { common, SUN, shadows, shapes, shared } from "./shading";
import { grid, orient, paint, roundedRect, slab, smooth, spline, steps } from "./shapes";

/**
 * The business cabin inside the plane, built for the shot that ends in it: the camera comes in through a window on
 * the left, beside an empty window seat, and turns to the laptop on its table, which faces the seat, turned a little
 * toward the window. Ahead, rows of suites in their shells, one by each window and two side by side in the middle,
 * all facing forward, about half of them taken: passengers reading, resting, looking out. Overhead, the bins with
 * their reading lights and the arched ceiling with its light cove; underfoot, carpet, with the aisles' runners and
 * path lights; at the front, the bulkhead. The sun comes in through the right-hand windows in shafts and patches.
 * All of it white and grey, like the page.
 */

/** The cabin shader's parts (the laptop's own are LAPTOP_PART). */
const PART = {
  plain: 0,
  wall: 1,
  floor: 2,
  ceiling: 3,
  table: 7,
  coffee: 8,
  reveal: 9,
  bin: 10,
  service: 11,
  shell: 12,
  cushion: 13,
  display: 14,
  person: 15,
  head: 16,
  bulkhead: 17,
} as const;

const UP = new Vector3(0, 1, 0);
/** The rows, one every 1.27 m along the cabin. */
const ROW = 1.27;
/** The passenger's seat, beside the open window: its middle, on the floor. */
const SEAT = { x: -2.0, z: layout.hero.z + 0.08 } as const;
/** The seats' middles across the cabin: by each window, and the pair in the middle, staggered half a seat back. */
const COLUMNS = [
  { x: -2.0, stagger: 0, console: -1 },
  { x: -0.5, stagger: 0.45, console: -1 },
  { x: 0.5, stagger: 0.45, console: 1 },
  { x: 2.0, stagger: 0, console: 1 },
] as const;
const ROWS = [-4, -3, -2, -1, 0, 1, 2] as const;
/** How much of the cabin there is, along it: from the bulkhead at the front. */
const FROM = -20.55;
const TO = -11.0;
/** The windows the cabin has reveals for, on each side. */
const WINDOWS = Array.from({ length: 15 }, (_, k) => -19.6 + 0.6 * k);
/** The window's opening on the inside, larger than the pane. */
const INNER = { halfWidth: 0.205, halfHeight: 0.315, radius: 0.17 } as const;
const BEZEL = { halfWidth: 0.24, halfHeight: 0.35, radius: 0.2 } as const;
/**
 * How far each window's shade is drawn down (0 open, 1 shut), left side and right, by window. On the sunlit right,
 * shades are open or shut: a gap under one would be a bright slit.
 */
const SHADES = {
  left: [0.35, 0, 0, 0.6, 0, 0.15, 0, 0, 0, 0, 0.5, 0, 0, 0.3, 0],
  right: [0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0],
} as const;
/** The shells' height, low enough that a seated eye sees over them. */
const SHELL_HEIGHT = 0.98;

/**
 * The laptop: on the passenger's table, facing their seat, turned a third of a right angle toward the window. Placed
 * so that the camera, at rest `VIEWING` metres out along the screen's normal, is where the passenger's eyes would be.
 */
const TURN = -Math.PI / 6;
const VIEWING = 0.62;
const FACING = Laptop.screen.normal.clone().applyAxisAngle(UP, TURN);
/** How high the table is. */
const TABLE_HEIGHT = 0.75;
/**
 * The cabin's floor: where it puts the passenger's eyes, at rest on the laptop, level with the windows' middles, as
 * a widebody's windows are for its seated passengers (about 1.1 m below the fuselage's axis, room for the hold's
 * containers under it).
 */
const FLOOR = layout.window.y - (TABLE_HEIGHT + Laptop.screen.centre.y + FACING.y * VIEWING);
/** The underside of the bins: high enough over the seats to stand up under. */
const BINS = FLOOR + 1.6;
const TABLE_TOP = FLOOR + TABLE_HEIGHT;
const EYE = { x: SEAT.x, z: layout.hero.z + 0.02 } as const;
const OFFSET = Laptop.screen.centre.clone().applyAxisAngle(UP, TURN);
const LAPTOP_AT = new Vector3(EYE.x - FACING.x * VIEWING - OFFSET.x, TABLE_TOP, EYE.z - FACING.z * VIEWING - OFFSET.z);
const TABLE = { x: LAPTOP_AT.x + 0.03, z: LAPTOP_AT.z - 0.01, width: 0.64, depth: 0.54 } as const;
/** The coffee, on the table beside the laptop, on the aisle side. */
const CUP = { x: LAPTOP_AT.x + 0.255, z: LAPTOP_AT.z + 0.03, y: TABLE_TOP } as const;

interface Finish {
  readonly part: number;
  readonly albedo: number;
  readonly gloss: number;
}

/** A rounded box standing on `at`, turned by `yaw` and tipped by `pitch` (about x): `width` along x, `depth` along z. */
function box(
  size: { width: number; depth: number; height: number; corner?: number; edge?: number; detail?: number },
  finish: Finish,
  at: { x: number; y: number; z: number },
  yaw = 0,
  pitch = 0,
): BufferGeometry {
  const geometry = slab(size.width, size.depth, size.height, size.corner ?? 0.03, size.edge ?? 0.02, finish.part, {
    around: size.detail ?? 40,
    profile: 4,
  });
  paint(geometry, finish.part, finish.albedo, finish.gloss);
  geometry.applyMatrix4(
    new Matrix4().makeRotationY(yaw).multiply(new Matrix4().makeRotationX(pitch)).setPosition(at.x, at.y, at.z),
  );
  return geometry;
}

/** The sidewall's x at height y near the windows, on the left (it bows in a little above and below them). */
const wallAt = (y: number) => -2.662 + 0.2 * (y - layout.window.y) ** 2;
/** The skin's x at height y, on the left: where a window's reveal begins on the outside. */
const skinAt = (y: number) => -Math.sqrt(layout.radius ** 2 - y * y) + 0.003;

/** The sidewall's cross-section on the left, from the floor up to the bins. */
const wallProfile = spline([
  [-2.33, FLOOR],
  [-2.45, -0.75],
  [-2.56, -0.4],
  [-2.63, -0.1],
  [-2.665, 0.15],
  [wallAt(BINS), BINS],
]);

/** One side's sidewall (-1 left, 1 right): its windows are cut out by the shader. */
function sidewall(side: number): BufferGeometry {
  const geometry = grid(
    steps(FROM, TO, 90),
    steps(0, 1, 30),
    (z, t, out) => {
      const [x, y] = wallProfile(t);
      out.set(side * -x, y, z);
    },
    { part: PART.wall },
  );
  paint(geometry, PART.wall, 0.88, 0.12);
  return orient(geometry, (p) => new Vector3(-p.x, 0.1, 0));
}

/** A window's reveal (from its pane in the skin, flaring to the opening in the wall), its bezel, and its shade. */
function windowSet(side: number, z: number, drawn: number): BufferGeometry[] {
  const { window } = layout;
  const reveal = grid(
    steps(0, 1, 6),
    steps(0, 1, 41),
    (u, t, out) => {
      const flare = smooth(0, 1, u);
      const [across, up] = roundedRect(
        t,
        window.halfWidth + (INNER.halfWidth - window.halfWidth) * flare,
        window.halfHeight + (INNER.halfHeight - window.halfHeight) * flare,
        window.radius + (INNER.radius - window.radius) * flare,
      );
      const y = window.y + up;
      const x = skinAt(y) + (wallAt(y) - skinAt(y)) * u;
      out.set(side < 0 ? x : -x, y, z + across);
    },
    { part: PART.reveal },
  );
  paint(reveal, PART.reveal, 0.82, 0.1);
  const pieces = [orient(reveal, (p) => new Vector3(0, window.y - p.y, z - p.z))];

  const profile = spline([
    [0, 0.001],
    [0.08, 0.016],
    [0.75, 0.018],
    [0.95, 0.011],
    [1, -0.012],
  ]);
  const bezel = grid(
    steps(0, 1, 6),
    steps(0, 1, 41),
    (u, t, out) => {
      const [blend, standoff] = profile(u);
      const [across, up] = roundedRect(
        t,
        INNER.halfWidth + (BEZEL.halfWidth - INNER.halfWidth) * blend,
        INNER.halfHeight + (BEZEL.halfHeight - INNER.halfHeight) * blend,
        INNER.radius + (BEZEL.radius - INNER.radius) * blend,
      );
      const y = window.y + up;
      const x = wallAt(y) + standoff;
      out.set(side < 0 ? x : -x, y, z + across);
    },
    { part: PART.plain },
  );
  paint(bezel, PART.plain, 0.92, 0.3);
  pieces.push(orient(bezel, () => new Vector3(-side, 0, 0)));

  if (drawn > 0) {
    const top = window.y + INNER.halfHeight - 0.02;
    const bottom = top - drawn * (2 * INNER.halfHeight - 0.03);
    const shade = grid(
      steps(-0.19, 0.19, 2),
      steps(bottom, top, 2),
      (across, y, out) => {
        const x = wallAt(y) - 0.03;
        out.set(side < 0 ? x : -x, y, z + across);
      },
      { part: PART.plain },
    );
    paint(shade, PART.plain, 0.9, 0.06);
    pieces.push(orient(shade, () => new Vector3(-side, 0, 0)));
  }
  return pieces;
}

/** One side's overhead bins: their underside with the reading lights, their doors bulging out above the seats. */
function bins(side: number): BufferGeometry[] {
  const underside = grid(
    steps(FROM, TO, 2),
    steps(wallAt(BINS), -1.6, 2),
    (z, x, out) => out.set(side < 0 ? x : -x, BINS, z),
    { part: PART.service },
  );
  paint(underside, PART.service, 0.86, 0.15);
  const door = spline([
    [-1.6, BINS],
    [-1.53, BINS + 0.16],
    [-1.495, BINS + 0.38],
    [-1.51, 1.2],
    [-1.57, 1.35],
    [-1.47, 1.4],
  ]);
  const doors = grid(
    steps(FROM, TO, 60),
    steps(0, 1, 26),
    (z, t, out) => {
      const [x, y] = door(t);
      out.set(side < 0 ? x : -x, y, z);
    },
    { part: PART.bin },
  );
  paint(doors, PART.bin, 0.93, 0.35);
  return [orient(underside, () => new Vector3(0, -1, 0)), orient(doors, (p) => new Vector3(-p.x * 0.6, -0.4, 0))];
}

/** The ceiling, arched between the bins, with a cove of light down its middle. */
function ceiling(): BufferGeometry {
  const geometry = grid(
    steps(FROM, TO, 40),
    steps(-1.47, 1.47, 50),
    (z, x, out) => out.set(x, 1.4 + 0.38 * (1 - (x / 1.47) ** 2) ** 0.7, z),
    { part: PART.ceiling },
  );
  paint(geometry, PART.ceiling, 0.92, 0.1);
  return orient(geometry, () => new Vector3(0, -1, 0));
}

function floor(): BufferGeometry {
  const geometry = grid(steps(-2.36, 2.36, 2), steps(FROM, TO, 2), (x, z, out) => out.set(x, FLOOR, z), {
    part: PART.floor,
  });
  paint(geometry, PART.floor, 0.5, 0.02);
  return orient(geometry, () => new Vector3(0, 1, 0));
}

/** The bulkhead closing the cabin at the front: the shader draws its screen and the galley's curtains. */
function bulkhead(): BufferGeometry {
  const geometry = grid(steps(-2.7, 2.7, 2), steps(FLOOR, 2.0, 2), (x, y, out) => out.set(x, y, FROM), {
    part: PART.bulkhead,
  });
  paint(geometry, PART.bulkhead, 0.86, 0.12);
  return orient(geometry, () => new Vector3(0, 0, 1));
}

/** A body of revolution round an upright axis at `at`, its profile [radius, height] from the bottom middle. */
function turned(
  profile: readonly (readonly [number, number])[],
  at: { x: number; y: number; z: number },
  finish: Finish,
  params?: (position: Vector3) => readonly [number, number, number],
): BufferGeometry {
  const curve = spline(profile);
  const geometry = grid(
    steps(0, 1, profile.length * 5),
    steps(0, Math.PI * 2, 41),
    (u, angle, out) => {
      const [radius, height] = curve(u);
      out.set(at.x + radius * Math.cos(angle), at.y + height, at.z - radius * Math.sin(angle));
    },
    { part: finish.part, flip: true },
  );
  paint(geometry, finish.part, finish.albedo, finish.gloss, params);
  return geometry;
}

/** A capsule (a limb) from `from` to `to`, `radius` round; `params` gives its own coordinates, for the shader. */
function capsule(
  from: Vector3,
  to: Vector3,
  radius: number,
  finish: Finish,
  params?: (position: Vector3) => readonly [number, number, number],
): BufferGeometry {
  const length = from.distanceTo(to);
  const profile: [number, number][] = [];
  for (let k = 0; k <= 4; k++) {
    const angle = (k / 4) * (Math.PI / 2);
    profile.push([radius * Math.sin(angle), radius * (1 - Math.cos(angle))]);
  }
  for (let k = 0; k <= 4; k++) {
    const angle = (Math.PI / 2) * (1 - k / 4);
    profile.push([radius * Math.sin(angle), length - radius * (1 - Math.cos(angle))]);
  }
  const geometry = turned(profile, { x: 0, y: 0, z: 0 }, finish, params);
  const turn = new Quaternion().setFromUnitVectors(UP, to.clone().sub(from).normalize());
  return geometry.applyMatrix4(new Matrix4().compose(from, turn, new Vector3(1, 1, 1)));
}

/** A cup of coffee on its saucer, its handle out to the side. */
function coffee(at: { x: number; y: number; z: number }): BufferGeometry[] {
  const porcelain = { part: PART.plain, albedo: 0.94, gloss: 0.85 };
  const saucer = turned(
    [
      [0, 0.0],
      [0.046, 0.0],
      [0.06, 0.004],
      [0.069, 0.0105],
      [0.0695, 0.012],
      [0.066, 0.0115],
      [0.054, 0.0075],
      [0.04, 0.0045],
      [0, 0.0045],
    ],
    at,
    porcelain,
  );
  const cup = turned(
    [
      [0, 0.0045],
      [0.023, 0.0045],
      [0.028, 0.008],
      [0.035, 0.026],
      [0.0405, 0.052],
      [0.0432, 0.0705],
      [0.0428, 0.0735],
      [0.0402, 0.0722],
      [0.0378, 0.058],
      [0.031, 0.032],
      [0.021, 0.016],
      [0, 0.0145],
    ],
    at,
    porcelain,
  );
  const drink = turned(
    [
      [0, 0.0605],
      [0.037, 0.0605],
      [0.0384, 0.0612],
    ],
    at,
    { part: PART.coffee, albedo: 0.06, gloss: 0.7 },
  );
  const path = new CatmullRomCurve3([
    new Vector3(at.x + 0.038, at.y + 0.062, at.z),
    new Vector3(at.x + 0.058, at.y + 0.064, at.z),
    new Vector3(at.x + 0.064, at.y + 0.046, at.z),
    new Vector3(at.x + 0.052, at.y + 0.03, at.z),
    new Vector3(at.x + 0.034, at.y + 0.026, at.z),
  ]);
  const handle = new TubeGeometry(path, 24, 0.0042, 10);
  paint(handle, PART.plain, 0.94, 0.85);
  return [saucer, cup, drink, handle];
}

const SHELL: Finish = { part: PART.shell, albedo: 0.9, gloss: 0.32 };
const CAP: Finish = { part: PART.plain, albedo: 0.5, gloss: 0.55 };
const SEAT_FINISH: Finish = { part: PART.cushion, albedo: 0.74, gloss: 0.08 };
const TRIM: Finish = { part: PART.plain, albedo: 0.8, gloss: 0.6 };
const DARK: Finish = { part: PART.display, albedo: 0.03, gloss: 0.9 };

/** A passenger's pose: how far they lean back, which way their head turns and tips, and their shade. */
interface Pose {
  readonly lean: number;
  readonly turn: number;
  readonly tip: number;
  readonly clothes: number;
  readonly hair: number;
  /** Their hands: in the lap, or up, holding something to read. */
  readonly hands: "lap" | "reading";
}

/**
 * A passenger, seated, in their suite's frame (facing -z, the seat's middle on the floor at the origin): a body
 * shaped like one, without its features, the way a figure in an architect's model is.
 */
function person(pose: Pose): BufferGeometry[] {
  const clothes: Finish = { part: PART.person, albedo: pose.clothes, gloss: 0.06 };
  const pieces: BufferGeometry[] = [];
  const seat = FLOOR + 0.46;

  // The torso: its outline at heights up from the seat, as half its width and half its depth.
  const outline: [height: number, halfWidth: number, halfDepth: number][] = [
    [0.0, 0.0, 0.0],
    [0.0, 0.15, 0.11],
    [0.08, 0.17, 0.12],
    [0.24, 0.155, 0.105],
    [0.4, 0.18, 0.115],
    [0.49, 0.2, 0.095],
    [0.54, 0.155, 0.07],
    [0.565, 0.065, 0.05],
    [0.57, 0.0, 0.0],
  ];
  const widths = spline(outline.map(([h, w]) => [w, h] as const));
  const depths = spline(outline.map(([h, , d]) => [d, h] as const));
  const torso = grid(
    steps(0, 1, 30),
    steps(0, Math.PI * 2, 37),
    (v, angle, out) => {
      const [halfWidth, height] = widths(v);
      const [halfDepth] = depths(v);
      const c = Math.cos(angle);
      const s = Math.sin(angle);
      // A rounded box in section, the way a body is.
      out.set(halfWidth * Math.sign(c) * Math.abs(c) ** 0.7, height, halfDepth * Math.sign(s) * Math.abs(s) ** 0.7);
    },
    { part: PART.person },
  );
  paint(torso, PART.person, pose.clothes, 0.06);
  orient(torso, (p) => new Vector3(p.x, 0, p.z));
  const back = new Matrix4().makeRotationX(pose.lean).setPosition(0, seat, 0.14);
  torso.applyMatrix4(back);
  pieces.push(torso);

  // Where the body's points are, leaning back with it.
  const at = (x: number, y: number, z: number) => new Vector3(x, y, z).applyMatrix4(back);
  // Neck and head, the head turned and tipped.
  pieces.push(
    capsule(at(0, 0.5, 0.0), at(0, 0.66, -0.01), 0.048, { part: PART.head, albedo: pose.hair, gloss: 0.1 }, () => [
      0, -1, -1,
    ]),
  );
  const head = turned(
    Array.from({ length: 9 }, (_, k) => {
      const angle = -Math.PI / 2 + (k / 8) * Math.PI;
      return [0.079 * Math.cos(angle), 0.112 * (1 + Math.sin(angle))] as const;
    }),
    { x: 0, y: -0.112, z: 0 },
    { part: PART.head, albedo: pose.hair, gloss: 0.12 },
  );
  head.applyMatrix4(new Matrix4().makeScale(1, 1, 1.18));
  const centre = at(0, 0.73, -0.02);
  head.applyMatrix4(
    new Matrix4()
      .makeRotationY(pose.turn)
      .multiply(new Matrix4().makeRotationX(pose.tip))
      .setPosition(centre.x, centre.y, centre.z),
  );
  pieces.push(head);

  // Arms: down from the shoulders to the elbows, by the sides; forearms forward, into the lap or up to read.
  for (const side of [-1, 1]) {
    const shoulder = at(side * 0.19, 0.47, 0.0);
    const elbow = at(side * 0.23, 0.2, -0.05);
    const hand =
      pose.hands === "lap" ? new Vector3(side * 0.1, seat + 0.16, -0.3) : new Vector3(side * 0.12, seat + 0.36, -0.36);
    pieces.push(capsule(shoulder, elbow, 0.048, clothes), capsule(elbow, hand, 0.04, clothes));
  }
  // Thighs, forward along the seat.
  for (const side of [-1, 1]) {
    pieces.push(
      capsule(new Vector3(side * 0.095, seat + 0.07, 0.08), new Vector3(side * 0.1, seat + 0.1, -0.36), 0.075, clothes),
    );
  }
  if (pose.hands === "reading") {
    // A book, open, in their hands.
    pieces.push(
      box(
        { width: 0.26, depth: 0.03, height: 0.19, corner: 0.006, edge: 0.004, detail: 12 },
        TRIM,
        { x: 0, y: seat + 0.3, z: -0.4 },
        0,
        -0.5,
      ),
    );
  }
  return pieces;
}

/**
 * A suite, in its own frame (the passenger facing -z, the seat's middle on the floor at the origin): a shell round the
 * seat, behind it and on one side (`wall`, -1 left or 1 right), capped in a darker trim; the seat with its arm,
 * back and headrest; a console on the other side (`console`) with a lamp and the seat's controls; a table, folded
 * away unless `table`, and a screen on the back of the shell ahead. The passenger's own, `hero`, has no table here:
 * theirs is set for the laptop.
 */
function suite(options: { console: number; wall: number; hero: boolean; table: boolean }): BufferGeometry[] {
  const { console: side, wall } = options;
  const pieces: BufferGeometry[] = [];
  const shellWall = (x: number, z: number, length: number, yaw: number, height = SHELL_HEIGHT) => {
    const panel = box(
      { width: length, depth: 0.05, height, corner: 0.025, edge: 0.02, detail: 28 },
      SHELL,
      { x, y: FLOOR, z },
      yaw,
    );
    // Its own coordinates are where it is in the suite: the shader tells the shell's outside by them.
    paint(panel, SHELL.part, SHELL.albedo, SHELL.gloss);
    pieces.push(
      panel,
      box(
        { width: length + 0.01, depth: 0.065, height: 0.04, corner: 0.03, edge: 0.018, detail: 28 },
        CAP,
        { x, y: FLOOR + height - 0.02, z },
        yaw,
      ),
    );
  };
  // The shell: behind the seat, and along one side (but for the passenger's own, open to the aisle).
  shellWall(0, 0.5, 1.0, 0);
  if (!options.hero) shellWall(wall * 0.5, -0.06, 1.1, Math.PI / 2);
  // The seat: cushion, arm on the shell's side, back and headrest, reclined a touch.
  pieces.push(
    box({ width: 0.54, depth: 0.58, height: 0.14, corner: 0.06, edge: 0.05, detail: 28 }, SEAT_FINISH, {
      x: 0,
      y: FLOOR + 0.32,
      z: 0.0,
    }),
    box({ width: 0.08, depth: 0.5, height: 0.26, corner: 0.035, edge: 0.03, detail: 24 }, SEAT_FINISH, {
      x: wall * 0.32,
      y: FLOOR + 0.36,
      z: 0.0,
    }),
    box(
      { width: 0.54, depth: 0.12, height: 0.66, corner: 0.05, edge: 0.05, detail: 28 },
      SEAT_FINISH,
      { x: 0, y: FLOOR + 0.42, z: 0.32 },
      0,
      0.18,
    ),
    box(
      { width: 0.38, depth: 0.1, height: 0.2, corner: 0.05, edge: 0.04, detail: 24 },
      SEAT_FINISH,
      { x: 0, y: FLOOR + 1.04, z: 0.44 },
      0,
      0.18,
    ),
  );
  // The console on the other side: its top, the seat's controls set into it, and a lamp.
  pieces.push(
    box({ width: 0.22, depth: 0.95, height: 0.62, corner: 0.02, edge: 0.012, detail: 24 }, TRIM, {
      x: side * 0.4,
      y: FLOOR,
      z: -0.06,
    }),
    box({ width: 0.07, depth: 0.14, height: 0.012, corner: 0.012, edge: 0.004, detail: 12 }, DARK, {
      x: side * 0.4,
      y: FLOOR + 0.62,
      z: 0.12,
    }),
    turned(
      [
        [0.0, 0.0],
        [0.022, 0.0],
        [0.018, 0.012],
        [0.006, 0.02],
        [0.006, 0.2],
        [0.03, 0.215],
        [0.032, 0.24],
        [0.0, 0.245],
      ],
      { x: side * 0.42, y: FLOOR + 0.62, z: 0.3 },
      TRIM,
    ),
  );
  // The screen on the back of the shell ahead, facing the seat (the passenger's own is put away).
  if (!options.hero) {
    pieces.push(
      box({ width: 0.36, depth: 0.014, height: 0.22, corner: 0.012, edge: 0.004, detail: 16 }, DARK, {
        x: 0,
        y: FLOOR + 0.62,
        z: -ROW + 0.5 + 0.035,
      }),
    );
  }
  if (options.table && !options.hero) {
    pieces.push(
      box({ width: 0.46, depth: 0.4, height: 0.025, corner: 0.03, edge: 0.008, detail: 24 }, TRIM, {
        x: side * 0.06,
        y: FLOOR + 0.72,
        z: -0.48,
      }),
    );
  }
  return pieces;
}

/** Who sits where: by row (0 the passenger's own), and column (left to right), and how. */
const PASSENGERS: readonly (readonly [row: number, column: number, pose: Pose])[] = [
  [-1, 0, { lean: 0.16, turn: 0.35, tip: 0.05, clothes: 0.25, hair: 0.16, hands: "lap" }],
  [-3, 0, { lean: 0.3, turn: -0.1, tip: 0.12, clothes: 0.46, hair: 0.3, hands: "lap" }],
  [-1, 1, { lean: 0.12, turn: -0.2, tip: 0.35, clothes: 0.4, hair: 0.2, hands: "reading" }],
  [-2, 1, { lean: 0.22, turn: 0.15, tip: 0.0, clothes: 0.22, hair: 0.12, hands: "lap" }],
  [-4, 1, { lean: 0.18, turn: 0.0, tip: 0.1, clothes: 0.35, hair: 0.25, hands: "lap" }],
  [0, 1, { lean: 0.2, turn: 0.4, tip: 0.06, clothes: 0.29, hair: 0.14, hands: "lap" }],
  [-2, 2, { lean: 0.26, turn: -0.3, tip: 0.08, clothes: 0.43, hair: 0.18, hands: "lap" }],
  [-3, 2, { lean: 0.14, turn: 0.1, tip: 0.3, clothes: 0.24, hair: 0.28, hands: "reading" }],
  [0, 2, { lean: 0.24, turn: -0.15, tip: 0.04, clothes: 0.37, hair: 0.1, hands: "lap" }],
  [-1, 3, { lean: 0.2, turn: -0.45, tip: 0.04, clothes: 0.31, hair: 0.22, hands: "lap" }],
  [-2, 3, { lean: 0.32, turn: 0.0, tip: 0.18, clothes: 0.27, hair: 0.15, hands: "lap" }],
  [-4, 3, { lean: 0.16, turn: -0.2, tip: 0.0, clothes: 0.45, hair: 0.3, hands: "lap" }],
  [1, 3, { lean: 0.2, turn: -0.3, tip: 0.1, clothes: 0.33, hair: 0.18, hands: "lap" }],
];

/** Every suite, and who's in them, and the passenger's own table with the laptop and coffee on it. */
function suites(): BufferGeometry[] {
  const pieces: BufferGeometry[] = [];
  const place = (parts: BufferGeometry[], x: number, z: number) => {
    const matrix = new Matrix4().makeTranslation(x, 0, z);
    for (const part of parts) part.applyMatrix4(matrix);
    pieces.push(...parts);
  };
  for (const row of ROWS) {
    for (const [index, column] of COLUMNS.entries()) {
      const hero = row === 0 && index === 0;
      // Window suites have their shell on the aisle side; the middle pair, on theirs.
      const wall = index === 0 ? 1 : index === 3 ? -1 : column.console;
      const z = SEAT.z + row * ROW + column.stagger;
      place(suite({ console: column.console, wall, hero, table: (row + index) % 3 === 0 }), column.x, z);
    }
  }
  for (const [row, index, pose] of PASSENGERS) {
    const column = COLUMNS[index];
    if (column == null) continue;
    place(person(pose), column.x, SEAT.z + row * ROW + column.stagger);
  }
  // The passenger's table, on a stem from the floor, with the laptop on it, and their coffee.
  pieces.push(
    box(
      { width: TABLE.width, depth: TABLE.depth, height: 0.024, corner: 0.035, edge: 0.007, detail: 32 },
      { part: PART.table, albedo: 0.16, gloss: 0.55 },
      { x: TABLE.x, y: TABLE_TOP - 0.024, z: TABLE.z },
    ),
    box({ width: 0.06, depth: 0.05, height: TABLE_TOP - 0.024 - FLOOR, corner: 0.02, edge: 0.01, detail: 16 }, TRIM, {
      x: TABLE.x - 0.18,
      y: FLOOR,
      z: TABLE.z,
    }),
    ...coffee(CUP),
  );
  return pieces;
}

function interior(): BufferGeometry {
  const parts = [
    sidewall(-1),
    sidewall(1),
    ...WINDOWS.flatMap((z, k) => [...windowSet(-1, z, SHADES.left[k] ?? 0), ...windowSet(1, z, SHADES.right[k] ?? 0)]),
    ...bins(-1),
    ...bins(1),
    ceiling(),
    floor(),
    bulkhead(),
    ...suites(),
  ];
  const merged = mergeGeometries(parts);
  for (const part of parts) part.dispose();
  return merged;
}

const vertex = /* glsl */ `
  attribute vec3 surface;
  attribute float part;
  attribute vec2 finish;
  varying vec3 vWorld;
  varying vec3 vNormal;
  varying vec3 vSurface;
  varying float vPart;
  varying vec2 vFinish;

  void main() {
    vec4 world = modelMatrix * vec4(position, 1.0);
    vWorld = world.xyz;
    vNormal = normalize(mat3(modelMatrix) * normal);
    vSurface = surface;
    vPart = part;
    vFinish = finish;
    gl_Position = projectionMatrix * viewMatrix * world;
  }
`;

/** The windows' openings in the sidewalls. */
const openings = /* glsl */ `
  const float WINDOW_Y = ${layout.window.y};
  const float FIRST = ${(WINDOWS[0] ?? 0).toFixed(2)};
  const float LAST = ${(WINDOWS[WINDOWS.length - 1] ?? 0).toFixed(2)};

  float openingAt(vec2 zy) {
    float k = clamp(floor((zy.x - FIRST) / ${layout.window.pitch} + 0.5), 0.0, (LAST - FIRST) / ${layout.window.pitch});
    vec2 q = vec2(zy.x - FIRST - k * ${layout.window.pitch}, zy.y - WINDOW_Y);
    return roundedBox(q, vec2(${INNER.halfWidth}, ${INNER.halfHeight}), ${INNER.radius});
  }
`;

const fragment = /* glsl */ `
  ${common}
  ${shadows}
  ${shapes}
  ${openings}
  uniform sampler2DShadow uShadow;
  uniform mat4 uShadowMatrix;
  uniform float uShadowTexel;
  uniform vec3 uScreen;
  uniform vec3 uScreenNormal;
  // The laptop on the table (its middle's x and z, and its turn's cosine and sine) and the cup beside it.
  uniform vec4 uLaptop;
  uniform vec2 uCup;
  varying vec3 vWorld;
  varying vec3 vNormal;
  varying vec3 vSurface;
  varying float vPart;
  varying vec2 vFinish;

  const float FLOOR = ${FLOOR.toFixed(3)};
  const float BINS = ${BINS.toFixed(3)};
  const float ROW = ${ROW};
  const float HERO_ROW = ${SEAT.z.toFixed(3)};

  float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
  }

  void main() {
    int part = int(vPart + 0.5);
    vec3 n = normalize(vNormal) * (gl_FrontFacing ? 1.0 : -1.0);
    vec3 view = normalize(cameraPosition - vWorld);
    float albedo = vFinish.x;
    float gloss = vFinish.y;
    float occlusion = 1.0;
    float glow = 0.0;
    float row = (vWorld.z - HERO_ROW) / ROW;

    if (part == ${PART.wall}) {
      if (openingAt(vec2(vWorld.z, vWorld.y)) < 0.0) discard;
      // The sidewall's panels meet every other window.
      float seam = stroke(fract((vWorld.z - FIRST + 0.3) / 1.2 + 0.5) * 1.2 - 0.6, 0.0015);
      albedo *= 1.0 - 0.12 * seam;
    } else if (part == ${PART.floor}) {
      // Carpet, with the aisles' runners a shade lighter and their path lights, a dot every 60 cm.
      albedo *= 0.88 + 0.24 * hash(floor(vWorld.xz * 260.0));
      float x = abs(vWorld.x);
      float runner = smoothstep(0.98, 1.0, x) * (1.0 - smoothstep(1.48, 1.5, x));
      albedo = mix(albedo, 0.66, runner * 0.6);
      vec2 spot = vec2(abs(x - 1.46), fract(vWorld.z / 0.6 + 0.5) * 0.6 - 0.3);
      glow += 2.6 * (1.0 - smoothstep(0.008, 0.016, length(spot)));
    } else if (part == ${PART.ceiling}) {
      // The cove of light down the ceiling's middle, and the panels' seams across it.
      float x = abs(vWorld.x);
      glow += 2.2 * (1.0 - smoothstep(0.16, 0.3, x));
      albedo *= 1.0 - 0.15 * stroke((fract(row + 0.5) - 0.5) * ROW, 0.003);
    } else if (part == ${PART.bin}) {
      // The bins' doors: a seam between each, and a grip along their lower edge.
      albedo *= 1.0 - 0.28 * stroke((fract(row) - 0.5) * ROW, 0.0025);
      albedo *= 1.0 - 0.2 * smoothstep(0.03, 0.0, abs(vWorld.y - BINS - 0.09));
    } else if (part == ${PART.service}) {
      // Under the bins, over each seat: two reading lights (on, here and there), an air vent between them.
      vec2 p = vec2(abs(vWorld.x) - 2.0, (fract(row + 0.5) - 0.5) * ROW);
      float lights = min(length(p - vec2(0.0, 0.14)), length(p - vec2(0.0, -0.14)));
      float on = step(0.62, hash(vec2(floor(row + 0.5), sign(vWorld.x))));
      glow += (1.0 - smoothstep(0.025, 0.031, lights)) * mix(0.3, 3.0, on);
      albedo *= 1.0 - 0.3 * stroke(lights - 0.034, 0.002);
      albedo *= 1.0 - 0.5 * (1.0 - smoothstep(0.016, 0.02, length(p - vec2(-0.16, 0.0))));
    } else if (part == ${PART.shell}) {
      // The shells' outsides, which the aisles and the rows behind look at, are upholstered a shade darker than
      // their insides (their own coordinates are where they are in their suite).
      float outside = dot(n, normalize(vec3(vSurface.x, 0.0, vSurface.z) + 1e-4));
      albedo *= mix(1.0, 0.66, smoothstep(0.1, 0.5, outside));
    } else if (part == ${PART.cushion} || part == ${PART.person}) {
      // Upholstery and clothes: a faint weave.
      albedo *= 0.96 + 0.06 * hash(floor(vWorld.xz * 900.0) + floor(vWorld.y * 900.0));
    } else if (part == ${PART.head}) {
      // Hair over the head's top and back (its own coordinates: the face toward -z), and skin.
      vec3 h = vSurface;
      float hairline = mix(0.045, -0.085, smoothstep(-0.1, 0.08, h.z));
      float hair = smoothstep(-0.01, 0.01, h.y - hairline);
      albedo = mix(0.6, vFinish.x, hair);
      gloss = mix(0.1, 0.3, hair);
    } else if (part == ${PART.display}) {
      // The screens in the seats: off, but for one here and there showing a soft light.
      float on = step(0.7, hash(vec2(floor(vWorld.z * 3.0), floor(vWorld.x * 3.0))));
      glow += on * 0.9 * (0.6 + 0.4 * smoothstep(-0.2, 0.2, vWorld.y - FLOOR - 0.8));
    } else if (part == ${PART.bulkhead}) {
      // The bulkhead ahead: a screen high in its middle, and the galley's curtains at the aisles' ends.
      vec2 p = vWorld.xy;
      float monitor = roundedBox(p - vec2(0.0, FLOOR + 1.91), vec2(0.4, 0.23), 0.02);
      float curtain = roundedBox(vec2(abs(p.x) - 1.24, p.y - FLOOR - 1.4), vec2(0.27, 1.4), 0.03);
      float inCurtain = 1.0 - smoothstep(-0.004, 0.004, curtain);
      albedo = mix(albedo, 0.62 * (0.9 + 0.1 * sin(p.x * 80.0)), inCurtain);
      albedo = mix(albedo, 0.04, 1.0 - smoothstep(-0.004, 0.004, monitor));
      gloss = mix(gloss, 0.9, 1.0 - smoothstep(-0.004, 0.004, monitor));
    } else if (part == ${LAPTOP_PART.body}) {
      // The laptop's base: the keyboard's well, darker and set in, and the trackpad's edge.
      if (vSurface.y > 0.0105) {
        float well = roundedBox(vSurface.xz - vec2(0.0, -0.0505), vec2(0.1365, 0.0485), 0.005);
        albedo *= mix(0.62, 1.0, smoothstep(-0.0004, 0.0004, well));
        float pad = roundedBox(vSurface.xz - vec2(0.0, 0.0695), vec2(0.061, 0.0375), 0.007);
        albedo *= 1.0 - 0.3 * stroke(pad, 0.00025);
        gloss = mix(gloss, 0.75, 1.0 - smoothstep(-0.0004, 0.0004, pad));
      }
    } else if (part == ${LAPTOP_PART.lid}) {
      // The lid's inside: black glass, with an aluminium rim.
      vec2 face = vec2(vSurface.x, vSurface.y - 0.106);
      if (vSurface.z < 0.0006 && roundedBox(face, vec2(0.1505, 0.1045), 0.0095) < 0.0) {
        albedo = 0.02;
        gloss = 0.9;
      }
    } else if (part == ${PART.table}) {
      // Contact shadows under the laptop, and a satin grain along the table.
      vec2 d = vWorld.xz - uLaptop.xy;
      vec2 local = vec2(uLaptop.z * d.x - uLaptop.w * d.y, uLaptop.w * d.x + uLaptop.z * d.y);
      float under = roundedBox(local, vec2(${Laptop.footprint.halfWidth.toFixed(4)}, ${Laptop.footprint.halfDepth.toFixed(4)}), 0.012);
      occlusion *= mix(0.3, 1.0, smoothstep(-0.003, 0.045, under));
      albedo *= 0.96 + 0.08 * hash(vec2(floor(vWorld.x * 900.0), floor(vWorld.z * 40.0)));
    } else if (part == ${PART.coffee}) {
      float edge = length(vWorld.xz - uCup) / 0.038;
      albedo = mix(0.05, 0.32, smoothstep(0.82, 1.0, edge));
    }
    // The saucer sits in its own small shadow on the table.
    float onTable = 1.0 - smoothstep(0.0, 0.02, abs(vWorld.y - ${CUP.y.toFixed(3)}));
    occlusion *= mix(1.0, mix(0.45, 1.0, smoothstep(0.06, 0.11, length(vWorld.xz - uCup))), onTable);

    // The light: the sun in through the right-hand windows, in patches; the glow of the windows on either side, the
    // sunlit ones brighter; the cove's light from overhead; the cabin behind; and the even light a white cabin
    // fills with.
    float sun = SUN * 1.15 * wrapped(dot(n, uSun), 0.02) * shadowFrom(uShadow, uShadowMatrix, uShadowTexel, vWorld + n * 0.008);
    float far = 1.35 * max(dot(n, normalize(vec3(1.0, 0.25, 0.0))), 0.0) * (0.5 + 0.5 * smoothstep(-1.5, 2.6, vWorld.x));
    float near = 0.6 * max(dot(n, normalize(vec3(-1.0, 0.25, 0.0))), 0.0) * (0.5 + 0.5 * smoothstep(1.5, -2.6, vWorld.x));
    float cove = 0.55 * max(n.y, 0.0) * smoothstep(-1.25, 1.4, vWorld.y);
    float behind = 0.3 * max(n.z, 0.0);
    // Low down, between the seats, less of all of it gets in.
    occlusion *= mix(0.55, 1.0, smoothstep(FLOOR, FLOOR + 0.7, vWorld.y));
    // The laptop's screen glows on what's in front of it and facing it.
    vec3 toScreen = uScreen - vWorld;
    float screen = step(0.0, dot(-toScreen, uScreenNormal)) * max(dot(n, normalize(toScreen)), 0.0) * 0.12 / (dot(toScreen, toScreen) * 9.0 + 0.04);
    float radiance = albedo * (sun + (0.7 + far + near + cove + behind) * occlusion + screen) + glow;

    float facing = max(dot(n, view), 0.0);
    float fresnel = 0.04 + 0.96 * pow(1.0 - facing, 5.0);
    vec3 r = reflect(-view, n);
    float surroundings = 1.5 + 1.2 * smoothstep(0.1, 0.9, r.x) + 0.5 * r.y;
    float glint = pow(max(dot(n, normalize(uSun + view)), 0.0), 120.0) * 6.0 * min(sun, 1.0);
    radiance += gloss * (fresnel * surroundings * occlusion * 0.6 + glint * 0.4);
    gl_FragColor = finish(vec3(radiance));
  }
`;

const depthFragment = /* glsl */ `
  ${shapes}
  ${openings}
  varying vec3 vWorld;
  varying float vPart;

  void main() {
    if (int(vPart + 0.5) == ${PART.wall} && openingAt(vec2(vWorld.z, vWorld.y)) < 0.0) discard;
    gl_FragColor = vec4(0.0);
  }
`;

/**
 * Shafts of sun through the right-hand windows: each window's open part carried along the sunlight across the
 * cabin, glowing faintly, brightest where it comes in and fading as it goes, faint seen face-on and stronger
 * edge-on, where more of it lies along the eye. Drawn as added light.
 */
function shafts(): Mesh {
  const travel = SUN.clone().negate();
  const reach = 2.6;
  const pieces: BufferGeometry[] = [];
  for (const [k, z] of WINDOWS.entries()) {
    const drawn = SHADES.right[k] ?? 0;
    const top = layout.window.y + INNER.halfHeight * (1 - 2 * drawn) - 0.03;
    const bottom = layout.window.y - INNER.halfHeight + 0.03;
    if (top - bottom < 0.1) continue;
    const middle = (top + bottom) / 2;
    const halfHeight = (top - bottom) / 2;
    pieces.push(
      grid(
        steps(0, 1, 2),
        steps(0, 1, 33),
        (u, t, out) => {
          const [across, up] = roundedRect(t, INNER.halfWidth - 0.02, halfHeight, Math.min(INNER.radius, halfHeight));
          out.set(-wallAt(middle + up) - 0.01, middle + up, z + across).addScaledVector(travel, u * reach);
        },
        { part: 0, params: (u) => [u, 0, 0] },
      ),
    );
  }
  const geometry = mergeGeometries(pieces);
  for (const piece of pieces) piece.dispose();
  const mesh = new Mesh(
    geometry,
    new ShaderMaterial({
      vertexShader: /* glsl */ `
        attribute vec3 surface;
        varying float vAlong;
        varying vec3 vWorld;
        varying vec3 vNormal;

        void main() {
          vAlong = surface.x;
          vec4 world = modelMatrix * vec4(position, 1.0);
          vWorld = world.xyz;
          vNormal = normalize(mat3(modelMatrix) * normal);
          gl_Position = projectionMatrix * viewMatrix * world;
        }
      `,
      fragmentShader: /* glsl */ `
        varying float vAlong;
        varying vec3 vWorld;
        varying vec3 vNormal;

        void main() {
          vec3 view = normalize(cameraPosition - vWorld);
          float edgeOn = 1.0 - abs(dot(normalize(vNormal), view));
          float strength = (1.0 - smoothstep(0.0, 1.0, vAlong)) * smoothstep(0.0, 0.08, vAlong);
          gl_FragColor = vec4(vec3(0.07 * strength * (0.25 + 0.75 * edgeOn * edgeOn)), 1.0);
        }
      `,
      transparent: true,
      depthWrite: false,
      blending: AdditiveBlending,
      side: DoubleSide,
    }),
  );
  mesh.renderOrder = 5;
  mesh.frustumCulled = false;
  return mesh;
}

/** The cabin's sun-shadow map, and the uniforms its shader reads it from. */
export interface Shadowing {
  readonly uShadow: IUniform<Texture | null>;
  readonly uShadowMatrix: IUniform<Matrix4>;
  readonly uShadowTexel: IUniform<number>;
}

export class Cabin {
  /** Where the cabin's sun-shadow map should look, and how much of the cabin around it, in metres. */
  static readonly light = { centre: new Vector3(0, -0.2, (FROM + TO) / 2), reach: 5.6, depth: 14 } as const;
  readonly group = new Group();
  /** What casts the cabin's shadows (the fuselage, with its windows open, casts too: the airliner's). */
  readonly casters = new Group();
  /** The laptop's screen, in the plane's frame: its middle, the way it faces, and how far out the camera rests. */
  readonly screen: { readonly centre: Vector3; readonly normal: Vector3; readonly viewing: number };
  private readonly brightness: { value: number };

  constructor(shadowing: Shadowing, anisotropy: number) {
    const laptop = new Group();
    laptop.position.copy(LAPTOP_AT);
    laptop.rotation.y = TURN;
    laptop.updateMatrixWorld();
    const centre = Laptop.screen.centre.clone().applyMatrix4(laptop.matrixWorld);
    this.screen = { centre, normal: FACING.clone(), viewing: VIEWING };

    const material = new ShaderMaterial({
      vertexShader: vertex,
      fragmentShader: fragment,
      uniforms: {
        ...shared,
        ...shadowing,
        uScreen: { value: centre },
        uScreenNormal: { value: FACING.clone() },
        uLaptop: { value: new Vector4(LAPTOP_AT.x, LAPTOP_AT.z, Math.cos(TURN), Math.sin(TURN)) },
        uCup: { value: [CUP.x, CUP.z] },
      },
    });
    const geometry = interior();
    const room = new Mesh(geometry, material);
    room.renderOrder = 0;
    this.group.add(room);

    const machine = new Laptop(material, anisotropy);
    this.brightness = machine.glow;
    laptop.add(machine.group);
    this.group.add(laptop, shafts());

    const depth = new ShaderMaterial({
      vertexShader: vertex,
      fragmentShader: depthFragment,
      side: DoubleSide,
      colorWrite: false,
    });
    this.casters.add(new Mesh(geometry, depth));
  }

  /** How bright the laptop's screen is. */
  set glow(value: number) {
    this.brightness.value = value;
  }
}
