import { CubicBezierCurve3, Vector3 } from "three";
import { layout } from "./airliner";

/**
 * The camera's flight in, as one move: where the camera is and looks at any point of it, from 0 (the opening shot) to
 * 1 (at rest on the laptop). The page's scroll plays it (motion/flight.ts).
 *
 * It's made the way it would be in Blender, of two keyframes. The camera travels one Bézier curve, from where it
 * waits off the plane's left side (a little above and ahead of the business cabin's open window, so it looks back at
 * it at a slight angle) to the passenger's eyes at the laptop: the curve's first handle leaves the opening toward the
 * window, and its second comes in square through it, so the camera goes in steady and level, its eyes already at the
 * seat's height. The camera tracks a target: the window as it sets off, then the laptop, the target sliding from one
 * to the other as the camera nears the window, so it comes through already turning to the laptop, and keeps it in
 * the middle of the frame as it glides into the seat. How the travel and the slide are timed are two-key eases, as
 * F-curves between two keyframes: each starts from rest and comes back to rest.
 */

/** What the camera does at a moment, and what the scene does with it. */
export interface Frame {
  readonly position: Vector3;
  readonly aim: Vector3;
  /** The camera's roll about where it looks, in radians. */
  roll: number;
  /** The vertical field of view, in degrees, for a 16:9 frame (narrower frames widen it to keep the width). */
  fov: number;
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
 * An ease between two keyframes, as an F-curve (or CSS's cubic-bezier): from 0 to 1, flat at both ends, so it leaves
 * and arrives at rest. Its handles reach `leave` and `arrive` of the way across (from the start, and back from the
 * end): a long handle lingers at its end, a short one gets going (or stops) briskly.
 */
class Ease {
  constructor(
    private readonly leave: number,
    private readonly arrive: number,
  ) {}

  at(x: number): number {
    // How far along the curve `x` is across (which always grows along it), found by halving; then its height there.
    const across = clamp01(x);
    let low = 0;
    let high = 1;
    for (let step = 0; step < 32; step++) {
      const s = (low + high) / 2;
      const r = 1 - s;
      if (3 * r * r * s * this.leave + 3 * r * s * s * (1 - this.arrive) + s * s * s < across) low = s;
      else high = s;
    }
    const s = (low + high) / 2;
    return s * s * (3 - 2 * s);
  }
}

/**
 * Where the camera waits, from the window: how far out, how far above, and how far ahead (toward the nose), which
 * makes the slight angle it looks back at the window from.
 */
const OPENING = { out: 26, up: 2.5, ahead: 4.2 } as const;
/** The curve's handles, in metres: leaving the opening (toward the window), and coming in to the seat (square). */
const HANDLES = { leave: 10, arrive: 2 } as const;
/** How the camera travels the curve, from the opening to the seat. */
const TRAVEL = new Ease(0.2, 0.85);
/** How far past the window the camera looks on the way in, so its look is steady as it goes through. */
const BEYOND = 3;
/**
 * When the target slides from the window to the laptop, and how: late enough that the window stays in the middle of
 * the frame on the way to it, and done just inside, so the camera comes in looking at the laptop.
 */
const LOOK = { from: 0.35, to: 0.68, ease: new Ease(0.6, 0.6) } as const;
/** How much higher than square on the screen the camera looks at rest, so the cabin beyond shows above it. */
const LIFT = (7 * Math.PI) / 180;

export class Shots {
  /** The window, and the way in through it. */
  private readonly window = layout.hero.clone();
  private readonly inward = new Vector3(1, 0, 0);
  /** The camera's path. */
  private readonly path: CubicBezierCurve3;
  /** How deep past the window the camera comes to rest. */
  private readonly depth: number;
  /** What the camera looks at on the way in, and at rest. */
  private readonly through: Vector3;
  private readonly laptop: Vector3;
  private readonly sway = new Vector3();
  private readonly off = new Vector3();

  constructor(screen: { readonly centre: Vector3; readonly normal: Vector3; readonly viewing: number }) {
    const { window, inward } = this;
    const start = window.clone().add(new Vector3(-OPENING.out, OPENING.up, -OPENING.ahead));
    // At rest, out along the screen's normal, where the passenger's eyes would be.
    const end = screen.centre.clone().addScaledVector(screen.normal, screen.viewing);
    this.depth = end.clone().sub(window).dot(inward);
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

    this.through = window.clone().addScaledVector(inward, BEYOND);
    const pitch = Math.asin(-screen.normal.y) + LIFT;
    const look = screen.normal
      .clone()
      .setY(0)
      .normalize()
      .negate()
      .multiplyScalar(Math.cos(pitch))
      .setY(Math.sin(pitch));
    this.laptop = end.clone().addScaledVector(look, screen.viewing);
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
   * the world's time) until it sets off.
   */
  at(progress: number, clock: number, frame: Frame): void {
    const { position, aim } = frame;
    this.path.getPoint(TRAVEL.at(progress), position);
    const looking = LOOK.ease.at(share(progress, LOOK.from, LOOK.to));
    aim.lerpVectors(this.through, this.laptop, looking);

    // The air's slow sway, which the camera rides until it sets off.
    const calm = 1 - ease(progress / 0.08);
    const s = clock * 0.37;
    this.sway.set(
      Math.sin(s * 1.3) + 0.6 * Math.sin(s * 2.9 + 1.1),
      Math.sin(s * 1.7 + 0.4) + 0.5 * Math.sin(s * 3.3 + 2.3),
      Math.sin(s * 1.1 + 2.0) * 0.6,
    );
    position.addScaledVector(this.sway, 0.09 * calm);
    aim.addScaledVector(this.sway, 0.05 * calm);
    frame.roll = 0.004 * Math.sin(clock * 0.5) * calm;

    // A wider lens inside, opening on the way to the seat; the window clearing as it nears, the laptop in focus at rest.
    const depth = this.off.copy(position).sub(this.window).dot(this.inward);
    frame.fov = 36 + 8 * ease(share(depth, -1, this.depth));
    frame.clear = ease(share(depth, -3.2, -0.9));
    frame.inside = depth > -8;
    frame.deck = depth < 0;
    frame.focus = ease(share(depth, 0.1 * this.depth, this.depth));
  }
}
