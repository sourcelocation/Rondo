import {
  AdditiveBlending,
  type BufferGeometry,
  DoubleSide,
  Group,
  Mesh,
  ShaderMaterial,
  Vector3,
  Vector4,
  type IUniform,
  type Matrix4,
  type Texture,
} from "three";
import { mergeGeometries } from "three/addons/utils/BufferGeometryUtils.js";
import { layout } from "./airliner";
import { Laptop } from "./laptop";
import type { Model } from "./model";
import type { Study } from "./study";
import { common, SUN, shadows, shapes, shared } from "./shading";
import { grid, roundedRect, steps } from "./shapes";

/**
 * The business cabin inside the plane, made in Blender for the shot that ends in it (motion/flight/model.ts): the camera comes in through a window on
 * the left, beside an empty window seat, and turns to the laptop on its table, which faces the seat, turned a little
 * toward the window. Ahead, rows of suites in their shells, one by each window and two side by side in the middle,
 * all facing forward, about half of them taken: passengers reading, resting, looking out. Overhead, the bins with
 * their reading lights and the arched ceiling with its light cove; underfoot, carpet, with the aisles' runners and
 * path lights; at the front, the bulkhead. The sun comes in through the right-hand windows in shafts and patches.
 * All of it white and grey, like the page.
 */

/** The cabin shader's parts, the model's `part` (the laptop's own are laptop.ts's LAPTOP_PART). */
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
/** How much of the cabin there is, along it: from the bulkhead at the front. */
const FROM = -20.55;
const TO = -11.0;
/** The windows the cabin has openings for, on each side. */
const WINDOWS = Array.from({ length: 15 }, (_, k) => -19.6 + 0.6 * k);
/** The window's opening on the inside, larger than the pane. */
const INNER = { halfWidth: 0.205, halfHeight: 0.315, radius: 0.17 } as const;
/**
 * How far each window's shade is drawn down (0 open, 1 shut), left side and right, by window. On the sunlit right,
 * shades are open or shut: a gap under one would be a bright slit.
 */
const SHADES = {
  left: [0.35, 0, 0, 0.6, 0, 0.15, 0, 0, 0, 0, 0.5, 0, 0, 0.3, 0],
  right: [0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0],
} as const;

/**
 * The laptop: on the passenger's table, facing their seat, turned a third of a right angle toward the window. Placed
 * so that the camera, at rest `VIEWING` metres out along the screen's normal, is where the passenger's eyes would be.
 */
const TURN = -Math.PI / 6;
const VIEWING = 0.74;
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
/** How far back from the window's line the passenger sits, so the laptop is on the table before them, clear of the shell ahead. */
const SITS_BACK = 0.12;
const EYE = { x: SEAT.x + FACING.x * SITS_BACK, z: layout.hero.z + 0.02 + FACING.z * SITS_BACK } as const;
const OFFSET = Laptop.screen.centre.clone().applyAxisAngle(UP, TURN);
const LAPTOP_AT = new Vector3(EYE.x - FACING.x * VIEWING - OFFSET.x, TABLE_TOP, EYE.z - FACING.z * VIEWING - OFFSET.z);
/** The coffee, on the table beside the laptop, on the aisle side. */
const CUP = { x: LAPTOP_AT.x + 0.28, z: LAPTOP_AT.z + 0.05, y: TABLE_TOP } as const;

/** The sidewall's x at height y near the windows, on the left (it bows in a little above and below them). */
const wallAt = (y: number) => -2.662 + 0.2 * (y - layout.window.y) ** 2;

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
  uniform sampler2D uKeys;
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
    ${Laptop.surface}
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

    // The light: the sun in through the right-hand windows, in patches; the sky's glow of the windows on either side,
    // the sunlit ones brighter; the cove's lilac mood light from overhead; the cabin behind; and the even light a white
    // cabin fills with.
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
    vec3 fill = (1.0 + behind) * mix(vec3(1.0), LILAC, 0.5) + (far + near) * SKYLIGHT * 1.2 + cove * LILAC;
    vec3 radiance = albedo * (sun * SUNLIGHT + fill * occlusion + screen) + glow * LILAC;

    float facing = max(dot(n, view), 0.0);
    float fresnel = 0.04 + 0.96 * pow(1.0 - facing, 5.0);
    vec3 r = reflect(-view, n);
    vec3 surroundings = (1.5 + 0.5 * r.y) * LILAC + 1.2 * smoothstep(0.1, 0.9, r.x) * SKYLIGHT;
    float glint = pow(max(dot(n, normalize(uSun + view)), 0.0), 120.0) * 6.0 * min(sun, 1.0);
    radiance += gloss * (fresnel * surroundings * occlusion * 0.6 + glint * 0.4 * SUNLIGHT);
    gl_FragColor = finish(radiance);
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
  /**
   * The laptop's screen, in the plane's frame: its middle, the way it faces, how far out the camera rests, and its
   * size, in metres.
   */
  readonly screen: {
    readonly centre: Vector3;
    readonly normal: Vector3;
    readonly viewing: number;
    readonly width: number;
    readonly height: number;
  };
  /** What's on the laptop's screen. */
  readonly study: Study;
  private readonly brightness: { value: number };

  /** The cabin and the laptop on its table, of the model's (model.ts), shaded in the sun by `shadowing`. */
  constructor(shadowing: Shadowing, anisotropy: number, model: Model) {
    const laptop = new Group();
    laptop.position.copy(LAPTOP_AT);
    laptop.rotation.y = TURN;
    laptop.updateMatrixWorld();
    const centre = Laptop.screen.centre.clone().applyMatrix4(laptop.matrixWorld);
    const { width, height } = Laptop.screen;
    this.screen = { centre, normal: FACING.clone(), viewing: VIEWING, width, height };

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
        uKeys: { value: model.keys },
      },
    });
    const room = new Mesh(model.cabin, material);
    room.renderOrder = 0;
    this.group.add(room);

    model.keys.anisotropy = anisotropy;
    const machine = new Laptop(material, anisotropy, model.laptop);
    this.brightness = machine.glow;
    this.study = machine.study;
    laptop.add(machine.group);
    this.group.add(laptop, shafts());

    const depth = new ShaderMaterial({
      vertexShader: vertex,
      fragmentShader: depthFragment,
      side: DoubleSide,
      colorWrite: false,
    });
    this.casters.add(new Mesh(model.cabin, depth));
  }

  /** How bright the laptop's screen is. */
  set glow(value: number) {
    this.brightness.value = value;
  }
}
