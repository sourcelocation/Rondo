import {
  BufferAttribute,
  BufferGeometry,
  Group,
  Line,
  LineBasicMaterial,
  LineSegments,
  Mesh,
  MeshBasicMaterial,
  SphereGeometry,
  Vector3,
} from "three";
import { type Frame, move, type Shots } from "./shots";

/** Whether the guides show (motion/flight/tuning.ts toggles them, with --tweak). */
export const guides = { shown: false };

/** How many steps the path is drawn in, from the opening to the eyes. */
const STEPS = 256;
/** How big the markers are, as a share of how far they are from the camera, so they read the same near and far. */
const MARKER = 0.006;

/**
 * The move drawn into the film, to tune it by, over everything: the path (on past its end, as far as the target goes),
 * its handles, where the target goes from and to on it, and the target.
 */
export class Guides {
  readonly group = new Group();
  private readonly path: Line;
  private readonly handles: LineSegments;
  private readonly target: Mesh;
  private readonly from: Mesh;
  private readonly to: Mesh;
  private readonly at = new Vector3();

  constructor() {
    const line = (color: number) =>
      new LineBasicMaterial({ color, depthTest: false, depthWrite: false, transparent: true });
    const dot = (color: number) =>
      new Mesh(
        new SphereGeometry(1, 16, 12),
        new MeshBasicMaterial({ color, depthTest: false, depthWrite: false, transparent: true }),
      );
    this.path = new Line(this.geometry(STEPS + 2), line(0xff2d95));
    this.handles = new LineSegments(this.geometry(4), line(0x2d9bff));
    this.target = dot(0xffd400);
    this.from = dot(0x2dff8a);
    this.to = dot(0xff6a2d);
    for (const object of [this.path, this.handles, this.target, this.from, this.to]) {
      object.frustumCulled = false;
      object.renderOrder = 1000;
      this.group.add(object);
    }
  }

  /** Lays the guides where `shots` has the move now, the camera and the target where `frame` has them. */
  update(shots: Shots, frame: Readonly<Frame>): void {
    const { v0, v1, v2, v3 } = shots.path;
    const { from, to } = move.target;
    const { at } = this;
    const path = this.path.geometry.getAttribute("position") as BufferAttribute;
    for (let step = 0; step <= STEPS + 1; step++) {
      if (step <= STEPS) shots.path.getPoint(step / STEPS, at);
      else shots.along(Math.min(to, 0), at);
      path.setXYZ(step, at.x, at.y, at.z);
    }
    path.needsUpdate = true;

    const handles = this.handles.geometry.getAttribute("position") as BufferAttribute;
    for (const [index, point] of [v0, v1, v3, v2].entries()) handles.setXYZ(index, point.x, point.y, point.z);
    handles.needsUpdate = true;

    shots.along(from, this.from.position);
    shots.along(to, this.to.position);
    this.target.position.copy(frame.aim);
    for (const marker of [this.target, this.from, this.to]) {
      marker.scale.setScalar(Math.max(marker.position.distanceTo(frame.position), 0.05) * MARKER);
    }
  }

  private geometry(points: number): BufferGeometry {
    return new BufferGeometry().setAttribute("position", new BufferAttribute(new Float32Array(points * 3), 3));
  }
}
