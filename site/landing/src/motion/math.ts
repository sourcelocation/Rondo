export const TAU = Math.PI * 2;

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

export function lerp(from: number, to: number, t: number): number {
  return from + (to - from) * t;
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
