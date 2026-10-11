import { Vector3 } from "three";

/**
 * The light every part of the flight is drawn in, and the GLSL they share. The world has no colour, like the page:
 * everything is white paint, porcelain or cloud, lit by one sun, the sky above and the clouds below, with ink for
 * glass and screens. Light is worked out in plain numbers (radiance), and a gentle curve turns it into shades of
 * grey, the haze far off coming out exactly as the page's white, so the film fades into the page at its edges.
 */

/**
 * Toward the sun: ahead of the plane, a little over its far side, and not high. It lights the clouds from the side,
 * so their billows stand out; lays the plane's shadow across the tops just behind and below it; leaves the side the
 * camera sees in the sky's soft light, white against the white; and comes into the cabin through the far side's
 * windows, in shafts and patches.
 */
export const SUN = new Vector3(0.32, 0.5, -0.8).normalize();

/** The uniforms every material reads, one object for all of them, so one change reaches them all. */
export const shared = {
  uSun: { value: SUN },
  /** Seconds the film has run, for whatever moves on its own. */
  uTime: { value: 0 },
  /** How far the clouds have travelled past the plane, in metres. */
  uScroll: { value: 0 },
};

/** Lighting, haze and the tone curve, for every fragment shader of the flight. */
export const common = /* glsl */ `
  uniform vec3 uSun;
  uniform float uTime;

  // Radiance: what a white surface facing the sun gives back, the sky's light from above and the clouds' from
  // below (a deck of sunlit cloud throws up a lot), and the white the haze and the brightest sky come out as (the
  // page's own).
  const float SUN = 2.7;
  const float SKY = 0.68;
  const float BOUNCE = 1.08;
  const float WHITE = 3.0;

  // Reinhard's curve, extended so WHITE comes out as white: highlights roll off instead of clipping.
  vec3 toneMap(vec3 x) {
    return x * (1.0 + x / (WHITE * WHITE)) / (1.0 + x);
  }

  vec3 toSrgb(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
  }

  vec4 finish(vec3 radiance) {
    return vec4(toSrgb(toneMap(radiance)), 1.0);
  }

  // The light from the sky and the clouds that reaches a surface facing n.
  float ambient(vec3 n) {
    return mix(BOUNCE, SKY, n.y * 0.5 + 0.5);
  }

  // Lambert's light, wrapped a little past the terminator, as paint and cloud scatter it.
  float wrapped(float nl, float wrap) {
    return clamp((nl + wrap) / (1.0 + wrap), 0.0, 1.0);
  }

  // The sky in a direction: white haze along the horizon, a little deeper overhead, brighter toward the sun.
  float skyAt(vec3 dir) {
    float up = clamp(dir.y, 0.0, 1.0);
    float sky = mix(WHITE * 1.02, 1.75, pow(smoothstep(0.0, 0.6, up), 0.75));
    float sun = max(dot(dir, uSun), 0.0);
    return sky + 0.55 * pow(sun, 8.0) + 1.6 * pow(sun, 120.0);
  }

  // What a glossy surface reflects: the sky above the horizon, the sunlit cloud tops below it.
  float environment(vec3 r) {
    float deck = 2.15 + 0.35 * r.y;
    return mix(deck, skyAt(r), smoothstep(-0.06, 0.04, r.y));
  }

  // How much of the haze lies between the eye and a point this far away.
  float haze(float distance) {
    return 1.0 - exp(-distance * 0.00036);
  }
`;

/** Shadow maps, sampled over a disc of hardware-filtered taps, for edges as soft as the sun's. */
export const shadows = /* glsl */ `
  const vec2 DISC[12] = vec2[12](
    vec2(-0.326, -0.406), vec2(-0.840, -0.074), vec2(-0.696, 0.457), vec2(-0.203, 0.621),
    vec2(0.962, -0.195), vec2(0.473, -0.480), vec2(0.519, 0.767), vec2(0.185, -0.893),
    vec2(0.507, 0.064), vec2(0.896, 0.412), vec2(-0.322, -0.933), vec2(-0.792, -0.598)
  );

  float shadowFrom(sampler2DShadow map, mat4 matrix, float texel, vec3 world) {
    vec4 projected = matrix * vec4(world, 1.0);
    vec3 at = projected.xyz / projected.w * 0.5 + 0.5;
    if (at.x <= 0.001 || at.x >= 0.999 || at.y <= 0.001 || at.y >= 0.999 || at.z >= 1.0) return 1.0;
    float depth = at.z - 0.0004;
    float lit = texture(map, vec3(at.xy, depth));
    for (int i = 0; i < 12; i++) lit += texture(map, vec3(at.xy + DISC[i] * texel, depth));
    return lit / 13.0;
  }
`;

/** A rounded rectangle's signed distance, for windows, doors and panels drawn into surfaces. */
export const shapes = /* glsl */ `
  float roundedBox(vec2 p, vec2 extent, float radius) {
    vec2 q = abs(p) - extent + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
  }

  // A line along a distance's zero, width wide, its edges smoothed over a pixel. Thinner than a pixel, it's drawn a
  // pixel or so wide and as much fainter, so a far-off seam fades away rather than breaking into dashes.
  float stroke(float distance, float width) {
    float pixel = max(fwidth(distance), 1e-6);
    float reach = max(width, pixel * 1.2);
    float line = 1.0 - smoothstep(reach - pixel * 0.6, reach + pixel * 0.6, abs(distance));
    return line * width / reach;
  }
`;
