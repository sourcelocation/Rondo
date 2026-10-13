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
import { type CloudMaps, cloudField } from "./clouds";
import { common, shadows, shapes, shared } from "./shading";
import { grid, steps } from "./shapes";

/**
 * The airliner: a modern twin-engined widebody in white paint, flying toward -z with its left side (-x) to the
 * camera. It's made in Blender (motion/flight/model.ts): a fuselage with a drooped nose and an upswept tail, wings
 * swept and bent up in flight with raked tips, engines with serrated nozzles, the tail, the fairings and antennas.
 * Windows, doors, the cockpit's glass, panel lines and the wings' moving surfaces are drawn into the paint by its
 * shader, crisp at any distance. One window, in business class on the left, is open: the camera goes in by it. The
 * plane holds still and the world moves past it, so its shadows are worked out once.
 */

/** The fuselage's radius, and where its nose and tail end, in metres along z. */
const RADIUS = 2.9;
const NOSE = -28;
const TAIL = 28.4;
/** How high the middle of the tail cone's end is, where the beacon is. */
const TAIL_CENTRE = 1.4;
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

/** Each part's number, which its shader draws it by: the model's `part` (model.ts). */
const PART = { skin: 0, wing: 1, tail: 2, cowl: 3, dark: 4, metal: 5, fairing: 6 } as const;

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
  vec3 glassAt(vec3 n, vec3 view) {
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
    vec3 paint = albedo * (1.0 - seams * 0.5) * (sun * SUNLIGHT + ambient(n));
    float facing = max(dot(n, view), 0.0);
    float fresnel = 0.04 + 0.96 * pow(1.0 - facing, 5.0);
    vec3 halfway = normalize(uSun + view);
    float glint = pow(max(dot(n, halfway), 0.0), 140.0) * 6.0 * step(0.0, dot(n, uSun)) * min(sun, 1.0);
    vec3 radiance = paint + gloss * (fresnel * environment(reflect(-view, n)) * 0.55 + glint * 0.5 * SUNLIGHT);
    radiance = mix(radiance, glassAt(n, view) + glint * 0.6 * SUNLIGHT, isGlass);

    // Where the plane dips into the clouds' tops (its engines, its belly), they veil it: a few steps from its
    // surface back toward the eye, through whatever cloud lies between.
    if (vWorld.y < -2.0) {
      float veil = 1.0;
      for (int i = 1; i <= 5; i++) {
        vec3 p = vWorld + view * float(i) * 1.1;
        veil *= exp(-cloudAt(p, topAt(p.xz), 1.0) * 0.42 * 1.1);
      }
      radiance = mix(2.4 * mix(SKYLIGHT, SUNLIGHT, 0.5), radiance, veil);
    }
    float distance = length(cameraPosition - vWorld);
    radiance = mix(radiance, hazeAt(-view), haze(distance));
    gl_FragColor = finish(radiance);
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
    vec3 radiance = mix(glassAt(n, view), hazeAt(-view), haze(length(cameraPosition - vWorld)));
    gl_FragColor = vec4(finish(radiance).rgb, inside * (1.0 - uClear));
  }
`;

/** The strobes and beacons: white flashes with a glow, drawn over everything, as additive light. */
const lightVertex = /* glsl */ `
  attribute vec4 light;
  uniform float uTime;
  varying vec2 vCorner;
  varying float vFlash;

  // A light swelling and fading smoothly once every PERIOD seconds, brightest the share at of the way into each, and
  // dark between.
  const float PERIOD = 2.4;
  float cycle(float at) {
    float wave = 0.5 - 0.5 * cos((uTime / PERIOD - at) * 6.2831853);
    return wave * wave * wave;
  }

  void main() {
    // The strobes swell and fade together; the beacons, top and bottom, in turn between them; the tail light stays on.
    float kind = light.w;
    float flash = kind < 0.5 ? cycle(0.0) : kind < 1.5 ? cycle(0.25 + 0.5 * step(0.0, -light.y)) * 0.8 : 0.22;
    vFlash = flash;
    vCorner = position.xy;
    vec4 centre = viewMatrix * vec4(light.xyz, 1.0);
    // Small, the glow growing only a little with the light: a point on the plane, not a flare.
    float size = 0.3 + 0.4 * flash;
    gl_Position = projectionMatrix * (centre + vec4(position.xy * size, 0.0, 0.0));
  }
`;

const lightFragment = /* glsl */ `
  varying vec2 vCorner;
  varying float vFlash;

  void main() {
    float r = length(vCorner);
    float glow = exp(-r * r * 9.0) * 0.35 + exp(-r * r * 90.0);
    gl_FragColor = vec4(vec3(glow * vFlash * 0.7), 1.0);
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

  /** The plane, of `geometry` (the model's airliner: model.ts), shaded in the sun by `shadowing`, over `clouds`. */
  constructor(shadowing: Shadowing, clouds: CloudMaps, geometry: BufferGeometry) {
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
      [0, TAIL_CENTRE, TAIL + 0.08, 2],
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
