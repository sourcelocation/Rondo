import * as Essentials from "@tweakpane/plugin-essentials";
import { CubicBezier, type CubicBezierApi } from "@tweakpane/plugin-essentials";
import type { BindingApi } from "@tweakpane/core";
import { type FolderApi, Pane } from "tweakpane";
import type { FlightFilm } from "../flight";
import { type Ease, timing } from "./shots";

/**
 * A panel for tuning the flight's timing as it plays, in development only (motion/flight.ts loads it): a slider that
 * scrolls the page to any point of the flight, and follows the scroll as it goes; the target the camera looks at, when
 * it sets off and how it goes; and how the camera travels its curve. Each change shows at once. Its eases are drawn as
 * F-curves whose handles are dragged sideways (each leaves and arrives flat, so they don't go up or down). Copy puts
 * the values on the clipboard as they're written in motion/flight/shots.ts, to paste over those in its `timing`.
 */
export class Tuning {
  private readonly pane = new Pane({ title: "Flight timing" });
  private readonly shown = { progress: 0 };
  private readonly progress: BindingApi<number>;
  /** Whether the slider is being moved to follow the scroll, not by hand. */
  private following = false;

  constructor(private readonly film: FlightFilm) {
    const { pane, shown } = this;
    pane.registerPlugin(Essentials);
    // Held in the screen's corner as the page scrolls, over everything (Tweakpane's own sits at the page's top).
    const wrapper = pane.element.parentElement;
    wrapper?.style.setProperty("position", "fixed");
    wrapper?.style.setProperty("z-index", "1000");

    this.progress = pane.addBinding(shown, "progress", { min: 0, max: 1, step: 0.001, label: "progress" });
    this.progress.on("change", ({ value }) => {
      if (!this.following) film.seek(value);
    });

    const target = pane.addFolder({ title: "Look target" });
    this.number(target, timing.target, "from", { min: 0, max: 0.95, label: "sets off" });
    this.ease(target, timing.target.ease, "ease");

    const camera = pane.addFolder({ title: "Camera", expanded: false });
    this.ease(camera, timing.travel, "travel");

    const copy = pane.addButton({ title: "Copy values" });
    copy.on("click", () => {
      void this.copy().then((done) => {
        copy.title = done ? "Copied" : "Copy failed: see the console";
        setTimeout(() => (copy.title = "Copy values"), 1500);
      });
    });
  }

  /** Moves the slider to where the scroll has flown the camera. */
  follow(progress: number): void {
    this.following = true;
    this.shown.progress = progress;
    this.progress.refresh();
    this.following = false;
  }

  private number<T extends Record<K, number>, K extends string & keyof T>(
    folder: FolderApi,
    of: T,
    key: K,
    range: { min: number; max: number; label: string },
  ): void {
    folder.addBinding(of, key, { ...range, step: 0.001 }).on("change", () => this.film.redraw());
  }

  /** An ease as its F-curve: dragging a handle sideways changes how it leaves or arrives. */
  private ease(folder: FolderApi, ease: Ease, label: string): void {
    const blade = folder.addBlade({
      view: "cubicbezier",
      label,
      value: [ease.leave, 0, 1 - ease.arrive, 1],
      expanded: true,
      picker: "inline",
    }) as CubicBezierApi;
    blade.on("change", ({ value }) => {
      // The handles stay level, as the ease's are: leaving and arriving at rest.
      if (value.y1 !== 0 || value.y2 !== 1) {
        blade.value = new CubicBezier(value.x1, 0, value.x2, 1);
        return;
      }
      ease.leave = value.x1;
      ease.arrive = 1 - value.x2;
      this.film.redraw();
    });
  }

  /**
   * Puts the values on the clipboard as motion/flight/shots.ts writes them: whether it could (if not, they're in the
   * console).
   */
  private async copy(): Promise<boolean> {
    const n = (value: number) => String(Number(value.toFixed(3)));
    const ease = (of: Ease) => `new Ease(${n(of.leave)}, ${n(of.arrive)})`;
    const { travel, target } = timing;
    const text = [`travel: ${ease(travel)},`, `target: { from: ${n(target.from)}, ease: ${ease(target.ease)} },`].join(
      "\n",
    );
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      console.warn(text);
      return false;
    }
  }
}
