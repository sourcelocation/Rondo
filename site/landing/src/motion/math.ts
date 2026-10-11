export const TAU = Math.PI * 2;

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

export function lerp(from: number, to: number, t: number): number {
  return from + (to - from) * t;
}

/** Where `value` is between `from` and `to`, from 0 to 1. */
export function progress(value: number, from: number, to: number): number {
  return clamp((value - from) / (to - from), 0, 1);
}

/**
 * Moves `from` toward `to` the same way at any frame rate: `rate` is how quickly, per second (about 1/rate seconds
 * to cover two thirds of the way).
 */
export function damp(from: number, to: number, rate: number, seconds: number): number {
  return lerp(from, to, 1 - Math.exp(-rate * seconds));
}

/** Rounds to a step, so a style that hardly changed isn't written again. */
export function quantize(value: number, step: number): number {
  return Math.round(value / step) * step;
}

/** A value on a spring: where it is, and how fast it's going. */
export interface Spring {
  value: number;
  velocity: number;
}

/**
 * Moves a spring toward `target`, as a damped oscillator: `stiffness` is how hard it pulls (per second squared),
 * `damping` how quickly it calms (per second). Under-damped, it overshoots and settles, which is what makes things
 * land. It steps in slices of a 120th of a second, so it's the same at any frame rate.
 */
export function stepSpring(spring: Spring, target: number, stiffness: number, damping: number, seconds: number): void {
  let left = Math.min(seconds, 0.1);
  while (left > 1e-6) {
    const step = Math.min(left, 1 / 120);
    spring.velocity += ((target - spring.value) * stiffness - spring.velocity * damping) * step;
    spring.value += spring.velocity * step;
    left -= step;
  }
}

/** Whether a spring has come to rest at `target`. */
export function settled(spring: Spring, target: number): boolean {
  return Math.abs(spring.value - target) < 1e-3 && Math.abs(spring.velocity) < 1e-3;
}

/** A pseudo-random number from 0 to 1 that's always the same for the same `seed`, so a layout looks hand-placed. */
export function noise(seed: number): number {
  const x = Math.sin(seed * 127.1 + 311.7) * 43758.5453;
  return x - Math.floor(x);
}
