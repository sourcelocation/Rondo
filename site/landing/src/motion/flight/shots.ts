import { CubicBezierCurve3, type Vector2, Vector3 } from "three";
import { layout } from "./airliner";

/**
 * The camera's flight in, as one move: where the camera is and looks at any point of it, from 0 (the opening shot) to
 * 1 (at rest on the laptop). The page's scroll plays it (motion/flight.ts).
 *
 * It's made the way it would be in Blender, of two keyframes. The camera travels one Bézier curve, from where it waits
 * off the plane's left side (a little above and ahead of the business cabin's open window, so it looks back at it at a
 * slight angle) to the passenger's eyes at the laptop: the curve's first handle leaves the opening toward the window,
 * and its second comes in square through it, so the camera goes in steady and level, its eyes already at the seat's
 * height. The camera looks at a target, an object of its own with its own keyframes: it waits on the window as the
 * camera flies in, then, as the camera nears, goes on ahead of it along the same way in, through the window to the
 * seat, and on to the laptop, where it comes to rest as the camera does. How the travel and the target are timed are
 * two-key eases, as F-curves between two keyframes: each starts from rest and comes back to rest.
 */

/** What the camera does at a moment, and what the scene does with it. */
export interface Frame {
  readonly position: Vector3;
  readonly aim: Vector3;
  /** The camera's roll about where it looks, in radians. */
  roll: number;
  /** How clear the open window's glass is: 0 as dark as the others, 1 gone. */
  clear: number;
  /** Whether the camera is near enough the window for the cabin to be seen. */
  inside: boolean;
  /** How much the lens focuses on the laptop, softening what's beyond it (0 to 1). */
  focus: number;
  /** Whether the cloud deck can be seen: not once the camera is through the window. */
  deck: boolean;
}

const clamp01 = (x: number) => Math.min(Math.max(x, 0), 1);
/** The minimum-jerk curve, 0 to 1: from rest to rest, its acceleration rising and falling without a corner. */
const ease = (x: number) => {
  const t = clamp01(x);
  return t * t * t * (t * (t * 6 - 15) + 10);
};
const share = (x: number, from: number, to: number) => clamp01((x - from) / (to - from));

/**
 * An ease between two keyframes, as an F-curve, from 0 to 1, given as CSS's cubic-bezier is: where its leaving handle
 * ends (`x1`, `y1`) and its arriving one (`x2`, `y2`). A level handle leaves (or arrives) at rest, a long one lingering
 * there, a short one getting going (or stopping) briskly; one along the diagonal goes at a steady pace.
 */
class Ease {
  constructor(
    private readonly x1: number,
    private readonly y1: number,
    private readonly x2: number,
    private readonly y2: number,
  ) {}

  at(x: number): number {
    // How far along the curve `x` is across (which always grows along it), found by halving; then its height there.
    const across = clamp01(x);
    let low = 0;
    let high = 1;
    for (let step = 0; step < 32; step++) {
      const s = (low + high) / 2;
      const r = 1 - s;
      if (3 * r * r * s * this.x1 + 3 * r * s * s * this.x2 + s * s * s < across) low = s;
      else high = s;
    }
    const s = (low + high) / 2;
    const r = 1 - s;
    return 3 * r * r * s * this.y1 + 3 * r * s * s * this.y2 + s * s * s;
  }
}

/** Where the open window's glass clears, in metres from it (in front of it below 0), as the camera nears it. */
const UNTINT = { from: -3.2, to: -0.9 } as const;
/** How much higher than square on the screen the camera looks at rest, so the cabin beyond shows above it. */
const LIFT = (7 * Math.PI) / 180;

/**
 * How the camera travels the curve, from the opening to the seat: nearly steadily, setting straight off from far out,
 * and settling lightly at the end, coming to the window five sixths of the way through (motion/flight.ts brings the
 * words of landing up then), leaving the rest for the cabin.
 */
const TRAVEL = new Ease(1 / 3, 1 / 3, 0.7, 1);
/**
 * When the target sets off from the window, as a share of the flight (it arrives at the laptop as the camera comes to
 * rest), and how it goes: off briskly enough to keep well ahead of the camera, which is fast as it reaches the window,
 * and settling on the laptop as slowly as it can, as the camera settles.
 */
const TARGET = { from: 0.66, ease: new Ease(0.2, 0, 0, 1) } as const;

/**
 * Where the camera waits, from the window: how far out, how far above, and how far ahead (toward the nose), which
 * makes the slight angle it looks back at the window from.
 */
const OPENING = { out: 137, up: 13.2, ahead: 22.1 } as const;
/** The curve's handles, in metres: leaving the opening (toward the window), and coming in to the seat (square). */
const HANDLES = { leave: 90, arrive: 2 } as const;
/**
 * How far the pointer's nudge moves the camera, across and up, as shares of how far it is from what it looks at (so it
 * reads the same out in the air as at the seat), for each share of the window the nudge has.
 */
const SHIFT = { across: 0.2, up: 0.3 } as const;
const UP = new Vector3(0, 1, 0);

export class Shots {
  /** The window, and the way in through it. */
  private readonly window = layout.hero.clone();
  private readonly inward = new Vector3(1, 0, 0);
  /** The camera's path. */
  private readonly path: CubicBezierCurve3;
  /** How deep past the window the camera comes to rest. */
  private readonly depth: number;
  /** The target's way: from the window, along the camera's way in to the seat, and on to the laptop. */
  private readonly target: CubicBezierCurve3;
  private readonly sway = new Vector3();
  private readonly off = new Vector3();
  private readonly reach = new Vector3();
  private readonly right = new Vector3();
  private readonly above = new Vector3();

  constructor(screen: { readonly centre: Vector3; readonly normal: Vector3; readonly viewing: number }) {
    const { window, inward } = this;
    // At rest, out along the screen's normal, where the passenger's eyes would be.
    const end = screen.centre.clone().addScaledVector(screen.normal, screen.viewing);
    this.depth = end.clone().sub(window).dot(inward);
    const start = window.clone().add(new Vector3(-OPENING.out, OPENING.up, -OPENING.ahead));
    const arrive = end.clone().addScaledVector(inward, -HANDLES.arrive);
    const leave = start.clone().add(window.clone().sub(start).setLength(HANDLES.leave));
    this.path = new CubicBezierCurve3(start, leave, arrive, end);
    // The leaving handle, moved across (not along) the way in, just so that the curve goes through the window's middle.
    const at = this.crossing();
    const r = 1 - at;
    const off = (point: Vector3) => point.clone().sub(window).projectOnPlane(inward);
    const across = off(start)
      .multiplyScalar(r * r * r)
      .addScaledVector(off(arrive), 3 * r * at * at)
      .addScaledVector(off(end), at * at * at)
      .divideScalar(-3 * r * r * at);
    leave.sub(off(leave)).add(across);

    const pitch = Math.asin(-screen.normal.y) + LIFT;
    const look = screen.normal
      .clone()
      .setY(0)
      .normalize()
      .negate()
      .multiplyScalar(Math.cos(pitch))
      .setY(Math.sin(pitch));
    const laptop = end.clone().addScaledVector(look, screen.viewing);
    // Leaving the window toward the seat, as the camera comes in, and coming to the laptop from the seat.
    this.target = new CubicBezierCurve3(window.clone(), end.clone(), end.clone(), laptop);
  }

  /** Where along the curve (by its own parameter) it passes the fuselage's side, the window. */
  private crossing(): number {
    const { inward, window } = this;
    let low = 0;
    let high = 1;
    for (let step = 0; step < 40; step++) {
      const t = (low + high) / 2;
      if (this.path.getPoint(t).sub(window).dot(inward) < 0) low = t;
      else high = t;
    }
    return (low + high) / 2;
  }

  /**
   * The camera `progress` of the way in (0 the opening shot, 1 at rest), drifting a little with the air (by `clock`,
   * the world's time) until it sets off, and moved a little by `nudge` (the pointer's push, across and down, in shares
   * of the window), still looking where it was.
   */
  at(progress: number, clock: number, nudge: Readonly<Vector2>, frame: Frame): void {
    const { position, aim } = frame;
    this.path.getPoint(TRAVEL.at(progress), position);
    this.target.getPoint(TARGET.ease.at(share(progress, TARGET.from, 1)), aim);

    // The air's slow sway, which the camera rides until it sets off.
    const calm = 1 - ease(progress / 0.05);
    const s = clock * 0.37;
    this.sway.set(
      Math.sin(s * 1.3) + 0.6 * Math.sin(s * 2.9 + 1.1),
      Math.sin(s * 1.7 + 0.4) + 0.5 * Math.sin(s * 3.3 + 2.3),
      Math.sin(s * 1.1 + 2.0) * 0.6,
    );
    position.addScaledVector(this.sway, 0.09 * calm);
    aim.addScaledVector(this.sway, 0.05 * calm);
    frame.roll = 0.004 * Math.sin(clock * 0.5) * calm;

    // The window clearing as the camera nears it, the laptop in focus at rest.
    const depth = this.off.copy(position).sub(this.window).dot(this.inward);
    frame.clear = ease(share(depth, UNTINT.from, UNTINT.to));
    frame.inside = depth > -8;
    frame.deck = depth < 0;
    // The lens starts focusing on the laptop as the window starts to clear, quickly at first.
    frame.focus = 1 - (1 - share(depth, UNTINT.from, this.depth)) ** 2;

    // The pointer's nudge: the camera moved across and up the way it was pushed, still looking at the same place.
    // Held still through the window, whose frame is too close to move past, and back as the camera settles at the seat.
    const free = Math.max(1 - ease(share(depth, -6, -2)), ease(share(depth, 0, this.depth)));
    const { reach, right, above } = this;
    reach.copy(position).sub(aim);
    const distance = reach.length() * free;
    right.crossVectors(UP, reach).normalize();
    above.crossVectors(reach, right).normalize();
    position
      .addScaledVector(right, nudge.x * SHIFT.across * distance)
      .addScaledVector(above, -nudge.y * SHIFT.up * distance);
  }
}
