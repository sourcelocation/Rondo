import {
  BufferAttribute,
  BufferGeometry,
  type Camera,
  type Data3DTexture,
  LinearFilter,
  Mesh,
  OrthographicCamera,
  RepeatWrapping,
  Scene,
  ShaderMaterial,
  type Texture,
  Vector2,
  WebGL3DRenderTarget,
  WebGLRenderTarget,
  type IUniform,
  type Matrix4,
  type WebGLRenderer,
} from "three";
import { common, shared } from "./shading";

/**
 * The cloud sea the plane skims: a deck of cumulus, drawn as a volume. Each pixel's ray is marched into the deck,
 * gathering the light scattered toward the eye as it goes and dimming by what's in front, so the clouds have depth:
 * soft, see-through edges, billows that shade each other, light glowing through their rims with the sun beyond,
 * and the plane's own shadow lying inside them.
 *
 * What the deck is made of is worked out once, on the GPU: a map of its tops (swells, domes and the billows on
 * them, a kilometre across, repeating) and a cube of 3D noise that wears those tops into rounded cauliflower and
 * thin wisps. The plane presses the tops down along its path, so it skims them, its engines just touching. The
 * march runs at a fraction of the screen's resolution, into a texture the scene then draws behind everything else;
 * clouds are soft enough that nothing is lost.
 */

/** Where the clouds are: the shape of the deck, for the volume's march and for the plane, to fog what dips in. */
export const cloudField = /* glsl */ `
  uniform sampler2D uTop;
  uniform highp sampler3D uDetail;
  uniform float uScroll;

  // The tops' map repeats every kilometre; its heights run from LOW to HIGH, in metres. The 3D detail repeats every
  // DETAIL metres.
  const float TOP_PERIOD = 1024.0;
  const float LOW = -18.0;
  const float HIGH = 6.0;
  const float DETAIL = 44.0;

  // The plane presses the clouds down beneath it: the highest a cloud may reach at a point. Lowest under the belly
  // and the engines; further out, just under the wings. From a bow wave ahead of the nose to far behind the tail,
  // where the channel fills back in.
  float ceilingAt(vec2 p) {
    float x = abs(p.x);
    float wing = -1.62 + (x - 2.2) * 0.105 + 0.0024 * (x - 2.2) * (x - 2.2) - 0.8;
    float under = mix(-4.35 + 0.0035 * x * x, wing, smoothstep(11.0, 19.0, x));
    float span = 1.0 - smoothstep(28.0, 44.0, x);
    float bow = smoothstep(-60.0, -24.0, p.y);
    float behind = smoothstep(26.0, 320.0, p.y);
    return mix(60.0, under + behind * 7.0, span * bow * (1.0 - behind * behind));
  }

  // The deck's top at (x, z), and the ceiling there: the map's height, squeezed into the room under the plane,
  // more and more the higher it would reach, so the tops flatten smoothly under it rather than being cut.
  vec2 topAt(vec2 xz) {
    vec2 t = textureLod(uTop, vec2(xz.x, xz.y - uScroll) / TOP_PERIOD, 0.0).rg;
    float height = LOW + (HIGH - LOW) * (t.x + t.y / 255.0);
    float ceiling = ceilingAt(xz);
    float base = ceiling - 3.4;
    float above = height - base;
    return vec2(above > 0.0 ? base + above * 2.2 / (2.2 + above) : height, ceiling);
  }

  // How dense the cloud is at p, from 0 to 1, given the deck's top there: solid deep down, its top raised and
  // lowered into billows by the coarse detail, and worn into wisps by the fine as far as near allows (it fades
  // with distance, where the fine detail would only shimmer). Nothing reaches above the plane's ceiling.
  float cloudAt(vec3 p, vec2 top, float near) {
    float depth = top.x - p.y;
    if (depth < -4.0) return 0.0;
    vec3 q = vec3(p.x, p.y, p.z - uScroll) / DETAIL;
    float coarse = texture(uDetail, q).r;
    // Cauliflower: smaller lumps on the billows, close up.
    float lumps = near > 0.0 ? (texture(uDetail, q * 2.3 + vec3(0.19, 0.53, 0.07)).a - 0.5) * 2.6 * near : 0.0;
    float body = clamp((depth + (coarse - 0.5) * 7.0 + lumps + 0.4) / 0.9, 0.0, 1.0);
    if (body > 0.0 && near > 0.0) {
      float fine = texture(uDetail, q * 3.17 + vec3(0.37, 0.11, 0.73)).g;
      float wear = (1.0 - fine) * 0.62 * near;
      body = clamp((body - wear) / max(1.0 - wear, 0.05), 0.0, 1.0);
    }
    return body * smoothstep(top.y + 0.2, top.y - 1.4, p.y);
  }

  // The same, coarser and cheaper: for the light's way to the sun.
  float cloudSoft(vec3 p) {
    vec2 top = topAt(p.xz);
    float depth = top.x - p.y;
    if (depth < -4.0) return 0.0;
    float coarse = texture(uDetail, vec3(p.x, p.y, p.z - uScroll) / DETAIL).r;
    return clamp((depth + (coarse - 0.5) * 7.0 + 0.4) / 1.6, 0.0, 1.0) * smoothstep(top.y + 0.2, top.y - 1.4, p.y);
  }
`;

const fullscreen = /* glsl */ `
  varying vec2 vScreen;

  void main() {
    vScreen = position.xy;
    gl_Position = vec4(position.xy, 1.0, 1.0);
  }
`;

/** The march: the sky, and in front of it the deck, its light gathered along each ray. */
const march = /* glsl */ `
  ${common}
  ${cloudField}
  uniform mat4 uProjectionInverse;
  uniform mat4 uCameraWorld;
  uniform sampler2D uOccupancy;
  uniform mat4 uOccupancyMatrix;
  uniform float uSteps;
  varying vec2 vScreen;

  // Extinction deep in a cloud, per metre, and toward the sun (less, as light that scatters still gets through).
  const float DENSITY = 1.2;
  const float LIGHT_DENSITY = 0.34;

  // Henyey and Greenstein's phase function, scaled so scattering evenly in every direction is 1.
  float phase(float cosine, float g) {
    float g2 = g * g;
    return (1.0 - g2) / pow(1.0 + g2 - 2.0 * g * cosine, 1.5);
  }

  // Light scattered toward the eye by a cloud this deep in its own shade (in optical depth toward the sun): a
  // forward lobe for the silver linings, a softer backward one, and fainter, broader octaves for the light that
  // has bounced around inside, which is what keeps a cloud's shadowed side bright.
  float scattered(float shade, float cosine) {
    float light = 0.0;
    float weight = 1.0;
    float reach = 1.0;
    float spread = 1.0;
    for (int i = 0; i < 3; i++) {
      float lobe = mix(phase(cosine * spread, -0.25), phase(cosine * spread, 0.62), 0.72);
      light += weight * exp(-shade * reach) * lobe;
      weight *= 0.34;
      reach *= 0.4;
      spread *= 0.5;
    }
    return light;
  }

  // How far a point is inside the deck's shade, toward the sun: four steps, each longer than the last, the first
  // through the full detail, so each small billow shades the next.
  float shadeAt(vec3 p, float near) {
    vec3 q = p + uSun * 0.45;
    float depth = cloudAt(q, topAt(q.xz), near) * 0.8;
    depth += cloudSoft(p + uSun * 1.5) * 1.3;
    depth += cloudSoft(p + uSun * 3.4) * 2.4;
    depth += cloudSoft(p + uSun * 7.8) * 5.0;
    return depth * LIGHT_DENSITY;
  }

  // Interleaved gradient noise: a fixed pattern that staggers where rays start, so steps don't band.
  float jitter(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
  }

  void main() {
    vec4 view = uProjectionInverse * vec4(vScreen, 1.0, 1.0);
    vec3 direction = normalize(mat3(uCameraWorld) * (view.xyz / view.w));
    vec3 origin = cameraPosition;
    float sky = skyAt(direction);

    // The deck lies between its lowest and highest tops (with room for billows); rays that never cross it see sky.
    float roof = HIGH + 4.0;
    float bottom = LOW - 6.0;
    float enter = 0.0;
    float leave = 6000.0;
    if (abs(direction.y) > 1e-4) {
      float toRoof = (roof - origin.y) / direction.y;
      float toBottom = (bottom - origin.y) / direction.y;
      enter = max(min(toRoof, toBottom), 0.0);
      leave = min(max(toRoof, toBottom), 6000.0);
    } else if (origin.y > roof || origin.y < bottom) {
      leave = -1.0;
    }

    float cosine = dot(direction, uSun);
    float transmittance = 1.0;
    float light = 0.0;
    float t = enter + jitter(gl_FragCoord.xy) * 0.6;
    for (int i = 0; i < 112; i++) {
      if (float(i) >= uSteps || t > leave || transmittance < 0.012) break;
      vec3 p = origin + direction * t;
      vec2 top = topAt(p.xz);
      // Above the tops (with room for the billows' rise) there's nothing: stride toward them.
      float clear = p.y - (min(top.x, top.y) + 4.0);
      if (clear > 0.0) {
        t += max(clear / (abs(direction.y) + 1.1), 0.4);
        continue;
      }
      float near = 1.0 - smoothstep(120.0, 420.0, t);
      float density = cloudAt(p, top, near);
      float stride = clamp(0.3 + t * 0.009, 0.3, 14.0);
      if (density > 0.004) {
        float extinction = density * DENSITY;
        // The plane's shadow, softer the deeper below it.
        vec4 seen = uOccupancyMatrix * vec4(p, 1.0);
        float below = clamp(-2.6 - p.y, 0.0, 14.0);
        float planeShade = mix(1.0, textureLod(uOccupancy, seen.xy * 0.5 + 0.5, 1.5 + log2(1.0 + below)).r, 0.75);
        // Sunlit edges facing away from the sun read a little darker (the powder effect): it rounds the billows.
        float powder = mix(1.0, 1.0 - exp(-extinction * 9.0), 0.4);
        float sun = SUN * 0.7 * scattered(shadeAt(p, near), cosine) * planeShade * powder;
        // Deep in the deck, less of the sky gets in.
        float ambient = 0.6 * mix(0.15, 1.0, exp(-max(top.x - p.y, 0.0) * 0.4));
        float radiance = mix(sun + ambient, WHITE, haze(t));
        float kept = exp(-extinction * stride);
        light += transmittance * radiance * (1.0 - kept);
        transmittance *= kept;
      }
      t += stride;
    }
    gl_FragColor = finish(vec3(light + transmittance * sky));
  }
`;

/** A random number generator for the bakers: three numbers from 0 to 1 for a cell and a seed. */
const random = /* glsl */ `
  uvec3 pcg(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    return v;
  }

  vec3 random(vec3 cell, float seed) {
    return vec3(pcg(uvec3(ivec3(cell) + ivec3(int(seed) * 7919 + 4099, 3001, 2003)))) / 4294967295.0;
  }
`;

/** Bakes the deck's tops: heights as 16 bits over two channels (so filtering still blends them), and the domes. */
const bakeTop = /* glsl */ `
  ${random}
  varying vec2 vScreen;

  // A field of round bumps scattered over a tile of cells, one or two to a cell, merged by a smooth maximum so they
  // swell out of each other the way billows do. Tiles repeat seamlessly.
  float domes(vec2 uv, float cells, float seed) {
    vec2 p = uv * cells;
    vec2 c = floor(p);
    float sum = 1.0;
    for (int j = -1; j <= 1; j++) {
      for (int i = -1; i <= 1; i++) {
        vec2 cell = c + vec2(i, j);
        vec2 wrapped = mod(cell, cells);
        for (int k = 0; k < 2; k++) {
          vec3 a = random(vec3(wrapped, float(k)), seed);
          vec3 b = random(vec3(wrapped, float(k) + 11.0), seed);
          if (k == 1 && b.z > 0.45) continue;
          vec2 o = p - (cell + a.xy);
          float reach = mix(0.58, 1.02, a.z);
          float d = dot(o, o) / (reach * reach);
          if (d < 1.0) sum += exp(4.5 * mix(0.5, 1.0, b.x) * pow(1.0 - d, 1.5));
        }
      }
    }
    return clamp(log(sum) / 4.5 / 1.1, 0.0, 1.0);
  }

  // Smooth value noise over a tile of cells, repeating.
  float value(vec2 uv, float cells, float seed) {
    vec2 p = uv * cells;
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = random(vec3(mod(i, cells), 0.0), seed).x;
    float b = random(vec3(mod(i + vec2(1.0, 0.0), cells), 0.0), seed).x;
    float c = random(vec3(mod(i + vec2(0.0, 1.0), cells), 0.0), seed).x;
    float d = random(vec3(mod(i + vec2(1.0, 1.0), cells), 0.0), seed).x;
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
  }

  void main() {
    vec2 uv = vScreen * 0.5 + 0.5;
    float swell = value(uv, 4.0, 3.0) * 0.55 + value(uv, 8.0, 5.0) * 0.28 + value(uv, 16.0, 7.0) * 0.17;
    float dome = domes(uv, 42.0, 11.0);
    float billow = domes(uv.yx, 128.0, 23.0);
    float height = -9.6 + 7.0 * (swell - 0.5) + 6.8 * dome + 2.6 * billow * (0.3 + 0.7 * dome);
    float level = clamp((height + 18.0) / 24.0, 0.0, 1.0) * 255.0;
    gl_FragColor = vec4(floor(level) / 255.0, fract(level), dome, billow);
  }
`;

/**
 * Bakes the detail, one slice of the cube at a time: red is Perlin noise worn by Worley cells (round, billowing),
 * green and blue Worley noise at finer and finer scales (for wisps), alpha Worley at the coarsest. Every channel
 * repeats seamlessly.
 */
const bakeDetail = /* glsl */ `
  ${random}
  uniform float uLayer;
  varying vec2 vScreen;

  // Worley noise: 1 at each cell's point, falling to 0 a cell away.
  float worley(vec3 p, float cells, float seed) {
    vec3 q = p * cells;
    vec3 c = floor(q);
    float nearest = 9.0;
    for (int k = -1; k <= 1; k++) {
      for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
          vec3 cell = c + vec3(i, j, k);
          vec3 o = q - (cell + random(mod(cell, cells), seed));
          nearest = min(nearest, dot(o, o));
        }
      }
    }
    return 1.0 - clamp(sqrt(nearest), 0.0, 1.0);
  }

  // Perlin's gradient noise, from -1 to 1 or so.
  float perlin(vec3 p, float cells, float seed) {
    vec3 q = p * cells;
    vec3 i = floor(q);
    vec3 f = fract(q);
    vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float corners[8];
    for (int n = 0; n < 8; n++) {
      vec3 corner = vec3(float(n & 1), float((n >> 1) & 1), float((n >> 2) & 1));
      vec3 gradient = normalize(random(mod(i + corner, cells), seed) * 2.0 - 1.0);
      corners[n] = dot(gradient, f - corner);
    }
    float near = mix(mix(corners[0], corners[1], u.x), mix(corners[2], corners[3], u.x), u.y);
    float far = mix(mix(corners[4], corners[5], u.x), mix(corners[6], corners[7], u.x), u.y);
    return mix(near, far, u.z) * 1.6;
  }

  void main() {
    vec3 p = vec3(vScreen * 0.5 + 0.5, uLayer);
    float wave = perlin(p, 4.0, 1.0) * 0.55 + perlin(p, 8.0, 2.0) * 0.3 + perlin(p, 16.0, 3.0) * 0.15;
    wave = clamp(wave * 0.5 + 0.5, 0.0, 1.0);
    float cells = worley(p, 4.0, 4.0) * 0.625 + worley(p, 8.0, 5.0) * 0.25 + worley(p, 16.0, 6.0) * 0.125;
    // Perlin worn by Worley: the noise's lows pulled down where the cells are thin, so it billows.
    float billow = clamp((wave - (cells - 1.0)) / (2.0 - cells), 0.0, 1.0);
    float wisps = worley(p, 8.0, 7.0) * 0.625 + worley(p, 16.0, 8.0) * 0.25 + worley(p, 32.0, 9.0) * 0.125;
    float finer = worley(p, 16.0, 10.0) * 0.625 + worley(p, 32.0, 11.0) * 0.375;
    gl_FragColor = vec4(billow, wisps, finer, cells);
  }
`;

/** One triangle over the whole frame, for passes that draw every pixel. */
export function cover(): BufferGeometry {
  const geometry = new BufferGeometry();
  geometry.setAttribute("position", new BufferAttribute(new Float32Array([-1, -1, 0, 3, -1, 0, -1, 3, 0]), 3));
  return geometry;
}

/** The deck's makings, baked on the GPU: its tops' map, and the detail its billows are worn by. */
export interface CloudMaps {
  readonly uTop: IUniform<Texture>;
  readonly uDetail: IUniform<Data3DTexture>;
}

/** Bakes the deck's makings, a few slices at a time with `pause` between them, so the page doesn't stall. */
export async function bakeClouds(renderer: WebGLRenderer, pause: () => Promise<void>): Promise<CloudMaps> {
  const camera = new OrthographicCamera();
  const quad = new Mesh(cover());
  quad.frustumCulled = false;
  const scene = new Scene();
  scene.add(quad);
  const previous = renderer.getRenderTarget();

  const top = new WebGLRenderTarget(1024, 1024, { depthBuffer: false, generateMipmaps: false });
  top.texture.wrapS = RepeatWrapping;
  top.texture.wrapT = RepeatWrapping;
  top.texture.minFilter = LinearFilter;
  top.texture.magFilter = LinearFilter;
  quad.material = new ShaderMaterial({ vertexShader: fullscreen, fragmentShader: bakeTop, depthTest: false });
  renderer.setRenderTarget(top);
  renderer.render(scene, camera);
  await pause();

  const size = 64;
  const detail = new WebGL3DRenderTarget(size, size, size, { depthBuffer: false, generateMipmaps: false });
  detail.texture.wrapS = RepeatWrapping;
  detail.texture.wrapT = RepeatWrapping;
  detail.texture.wrapR = RepeatWrapping;
  detail.texture.minFilter = LinearFilter;
  detail.texture.magFilter = LinearFilter;
  const layer = { value: 0 };
  quad.material = new ShaderMaterial({
    vertexShader: fullscreen,
    fragmentShader: bakeDetail,
    uniforms: { uLayer: layer },
    depthTest: false,
  });
  for (let slice = 0; slice < size; slice++) {
    layer.value = (slice + 0.5) / size;
    renderer.setRenderTarget(detail, slice);
    renderer.render(scene, camera);
    if (slice % 16 === 15) await pause();
  }
  renderer.setRenderTarget(previous);
  return { uTop: { value: top.texture }, uDetail: { value: detail.texture } };
}

/** The plane's outline as the sun sees it, which the clouds take their shade from. */
export interface Occupancy {
  readonly uOccupancy: IUniform<Texture | null>;
  readonly uOccupancyMatrix: IUniform<Matrix4>;
}

export class CloudVolume {
  /** What the scene draws behind everything: the march's result, filling whatever nothing else covers. */
  readonly backdrop: Mesh;
  private readonly target = new WebGLRenderTarget(1, 1, { depthBuffer: false, generateMipmaps: false });
  private readonly scene = new Scene();
  private readonly viewport = new Vector2(1, 1);
  /** The share of the frame's resolution the march runs at. */
  scale = 0.5;

  private readonly steps: { value: number };

  /** `steps` caps how far each ray is marched: fewer on small screens. */
  constructor(
    maps: CloudMaps,
    occupancy: Occupancy,
    camera: Camera,
    private readonly most: number,
  ) {
    this.steps = { value: most };
    this.target.texture.minFilter = LinearFilter;
    this.target.texture.magFilter = LinearFilter;
    const material = new ShaderMaterial({
      vertexShader: fullscreen,
      fragmentShader: march,
      uniforms: {
        ...shared,
        ...maps,
        ...occupancy,
        uProjectionInverse: { value: camera.projectionMatrixInverse },
        uCameraWorld: { value: camera.matrixWorld },
        uSteps: this.steps,
      },
      depthTest: false,
      depthWrite: false,
    });
    const quad = new Mesh(cover(), material);
    quad.frustumCulled = false;
    this.scene.add(quad);

    this.backdrop = new Mesh(
      cover(),
      new ShaderMaterial({
        vertexShader: fullscreen,
        fragmentShader: /* glsl */ `
          uniform sampler2D uClouds;
          uniform vec2 uViewport;

          void main() {
            gl_FragColor = texture(uClouds, gl_FragCoord.xy / uViewport);
          }
        `,
        uniforms: { uClouds: { value: this.target.texture }, uViewport: { value: this.viewport } },
        depthWrite: false,
      }),
    );
    this.backdrop.frustumCulled = false;
    // Drawn after everything opaque, so only the pixels nothing covers read it.
    this.backdrop.renderOrder = 3;
  }

  /** Sizes the march to a frame of `width` × `height` pixels. */
  setSize(width: number, height: number): void {
    this.viewport.set(width, height);
    this.target.setSize(Math.max(1, Math.round(width * this.scale)), Math.max(1, Math.round(height * this.scale)));
  }

  /**
   * Marches the deck as `camera` sees it, ready for the backdrop to draw; or, where the deck can't be seen (from
   * inside the cabin, only the sky shows through the windows), only the sky.
   */
  render(renderer: WebGLRenderer, camera: Camera, deck: boolean): void {
    this.steps.value = deck ? this.most : 0;
    const previous = renderer.getRenderTarget();
    renderer.setRenderTarget(this.target);
    renderer.render(this.scene, camera);
    renderer.setRenderTarget(previous);
  }
}
