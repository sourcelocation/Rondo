import { Vector2 } from "three";

/** How far a push carries, at most, in shares of the window. */
const MOST = 0.4;
/** How quickly a push fades once the pointer is still, per second: gone in a second or so. */
const FADE = 2;
/** How quickly the camera follows the push, per second: a moment behind it. */
const FOLLOW = 4;
/** Close enough to nothing to be still. */
const STILL = 1e-5;

/**
 * The pointer's nudge on the camera: moving the mouse moves the camera a little the same way, and once the mouse is
 * still the camera eases back to its place. What the pointer's movement adds fades on its own, and the camera follows
 * it a moment behind: two calm lags, one after the other, so it moves and comes back smoothly and never overshoots.
 */
export class Nudge {
  /** Where the camera is nudged, across and down in shares of the window. */
  readonly offset = new Vector2();
  /** Where the pointer's movement would have it, fading back to nothing. */
  private readonly pushed = new Vector2();

  /** The pointer moved `x` across and `y` down, in shares of the window. */
  push(x: number, y: number): void {
    this.pushed.set(this.pushed.x + x, this.pushed.y + y).clampLength(0, MOST);
  }

  /** Moves on by `seconds`: whether the camera moved. */
  update(seconds: number): boolean {
    const { offset, pushed } = this;
    if (offset.lengthSq() === 0 && pushed.lengthSq() === 0) return false;
    pushed.multiplyScalar(Math.exp(-FADE * seconds));
    offset.lerp(pushed, 1 - Math.exp(-FOLLOW * seconds));
    // Close enough to nothing, it's simply back.
    if (offset.lengthSq() < STILL * STILL && pushed.lengthSq() < STILL * STILL) {
      offset.set(0, 0);
      pushed.set(0, 0);
    }
    return true;
  }
}
