import * as Essentials from "@tweakpane/plugin-essentials";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { type FolderApi, Pane } from "tweakpane";
import { type FlightFilm, words } from "../flight";
import { guides } from "./guides";
import { Ease, move } from "./shots";

/**
 * A panel for tuning the flight, shown with --tweak (motion/flight.ts loads it): how long it scrolls for, the camera's
 * path and its timing along it, where on the path the target goes, when and with what timing, and when the words change
 * over; and the path and the target drawn into the film, to tune them by. Each change shows at once. Copy puts the values on the clipboard as their files write them.
 */
export class Tuning {
  private readonly pane = new Pane({ title: "Flight" });
  /** How long the flight scrolls for, in screens, as components/Flight.astro sets it. */
  private readonly length: { track: number };

  constructor(
    private readonly host: HTMLElement,
    flight: FlightFilm,
  ) {
    const { pane } = this;
    pane.registerPlugin(Essentials);
    // Held in the screen's top right corner as the page scrolls, over everything, clear of the sea's on the left.
    const wrapper = pane.element.parentElement;
    wrapper?.style.setProperty("position", "fixed");
    wrapper?.style.setProperty("z-index", "1000");
    // Wide enough for the timing curves' graphs.
    wrapper?.style.setProperty("width", "300px");
    this.length = { track: Number(getComputedStyle(host).getPropertyValue("--track")) };

    pane.addBinding(flight, "progress", {
      readonly: true,
      interval: 50,
      label: "scrolled",
      format: (v) => v.toFixed(3),
    });
    pane.addBinding(guides, "shown", { label: "show path + target" }).on("change", () => flight.retune());
    pane
      .addBinding(this.length, "track", { min: 0.5, max: 6, step: 0.01, label: "length (screens)" })
      .on("change", () => this.lengthen());

    const path = pane.addFolder({ title: "Path" });
    path.addBinding(move.path, "out", { min: 10, max: 300, step: 0.1, label: "start out (m)" });
    path.addBinding(move.path, "up", { min: -50, max: 50, step: 0.1, label: "start up (m)" });
    path.addBinding(move.path, "ahead", { min: -100, max: 100, step: 0.1, label: "start ahead (m)" });
    path.addBinding(move.path, "leave", { min: 0, max: 300, step: 0.1, label: "leave handle (m)" });
    path.addBinding(move.path, "arrive", { min: 0, max: 3, step: 0.01, label: "arrive handle (m)" });

    const camera = pane.addFolder({ title: "Camera" });
    this.timing(camera, move.camera, (ease) => (move.camera = ease));

    const target = pane.addFolder({ title: "Target" });
    target.addBinding(move.target, "from", { min: -3, max: 5, step: 0.001, label: "from (m from end)" });
    target.addBinding(move.target, "to", { min: -3, max: 5, step: 0.001, label: "to (m from end)" });
    target.addBinding(move.target, "start", { min: 0, max: 1, step: 0.001, label: "first key (scroll)" });
    target.addBinding(move.target, "end", { min: 0, max: 1, step: 0.001, label: "last key (scroll)" });
    this.timing(target, move.target.timing, (ease) => (move.target.timing = ease));

    const said = pane.addFolder({ title: "Words" });
    said.addBinding(words, "arrive", { min: 0, max: 1, step: 0.001, label: "above in" });
    said.addBinding(words, "change", { min: 0, max: 1, step: 0.001, label: "above → landing" });
    said.addBinding(words, "go", { min: 0, max: 1.5, step: 0.001, label: "landing out" });

    for (const folder of [path, camera, target, said]) folder.on("change", () => flight.retune());

    const copy = pane.addButton({ title: "Copy values" });
    copy.on("click", () => {
      void this.copy().then((done) => {
        copy.title = done ? "Copied" : "Copy failed: see the console";
        setTimeout(() => (copy.title = "Copy values"), 1500);
      });
    });
  }

  /** A timing curve, its two keyframes' handles dragged on a graph, given to `set` as it changes. */
  private timing(folder: FolderApi, ease: Ease, set: (ease: Ease) => void): void {
    const blade = folder.addBlade({
      view: "cubicbezier",
      value: [ease.x1, ease.y1, ease.x2, ease.y2],
      expanded: true,
      picker: "inline",
    }) as Essentials.CubicBezierApi;
    blade.on("change", ({ value }) => set(new Ease(...value.toObject())));
  }

  /** Lays the section out at its tuned length, and the scroll's triggers along it again. */
  private lengthen(): void {
    this.host.style.setProperty("--track", String(this.length.track));
    ScrollTrigger.refresh();
  }

  /** Puts the values on the clipboard as their files write them: whether it could (if not, they're in the console). */
  private async copy(): Promise<boolean> {
    const n = (value: number) => String(Number(value.toFixed(3)));
    const ease = ({ x1, y1, x2, y2 }: Ease) => `new Ease(${[x1, y1, x2, y2].map(n).join(", ")})`;
    const { path, camera, target } = move;
    const text = [
      "// components/Flight.astro",
      `--track: ${n(this.length.track)};`,
      "// motion/flight/shots.ts, move",
      `path: { out: ${n(path.out)}, up: ${n(path.up)}, ahead: ${n(path.ahead)}, leave: ${n(path.leave)}, arrive: ${n(path.arrive)} },`,
      `camera: ${ease(camera)},`,
      `target: { from: ${n(target.from)}, to: ${n(target.to)}, start: ${n(target.start)}, end: ${n(target.end)}, timing: ${ease(target.timing)} },`,
      "// motion/flight.ts",
      `export const words = { arrive: ${n(words.arrive)}, change: ${n(words.change)}, go: ${n(words.go)} };`,
    ].join("\n");
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      console.warn(text);
      return false;
    }
  }
}
