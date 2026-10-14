import { type FolderApi, Pane } from "tweakpane";
import { sea, wave } from "./field";

/**
 * A panel for tuning the AI section's sea and the shockwave that crosses it, shown with --tweak
 * (motion/assistant.ts loads it). Each change shows at once. Copy puts the values on the clipboard as
 * motion/assistant/field.ts writes its `sea` and `wave`.
 */
export class Tuning {
  private readonly pane = new Pane({ title: "Sea" });

  constructor() {
    const { pane } = this;
    // Held in the screen's top left corner as the page scrolls, over everything, clear of the flight's on the right.
    const wrapper = pane.element.parentElement;
    wrapper?.style.setProperty("position", "fixed");
    wrapper?.style.setProperty("z-index", "1000");
    wrapper?.style.setProperty("left", "8px");
    wrapper?.style.setProperty("right", "auto");

    const waves = pane.addFolder({ title: "Waves" });
    this.number(waves, sea, "scale", 0.1, 4, "scale");
    this.number(waves, sea, "height", 0, 1, "height");
    this.number(waves, sea, "sharpness", 0.5, 4, "sharpness");

    const shock = pane.addFolder({ title: "Shockwave" });
    this.number(shock, wave, "height", 0, 0.3, "crest height");
    this.number(shock, wave, "width", 0.05, 2, "crest width");
    this.number(shock, wave, "dip", 0, 1, "draw down");
    this.number(shock, wave, "ahead", 0.1, 6, "ahead");
    this.number(shock, wave, "reach", 0.1, 4, "reach");
    this.number(shock, wave, "flattened", 1, 20, "flatter by");
    this.number(shock, wave, "swell", 0, 3, "swell");

    const copy = pane.addButton({ title: "Copy values" });
    copy.on("click", () => {
      void this.copy().then((done) => {
        copy.title = done ? "Copied" : "Copy failed: see the console";
        setTimeout(() => (copy.title = "Copy values"), 1500);
      });
    });
  }

  private number<T extends Record<K, number>, K extends string & keyof T>(
    folder: FolderApi,
    of: T,
    key: K,
    min: number,
    max: number,
    label: string,
  ): void {
    folder.addBinding(of, key, { min, max, step: 0.001, label });
  }

  /**
   * Puts the values on the clipboard as motion/assistant/field.ts writes them: whether it could (if not, they're in
   * the console).
   */
  private async copy(): Promise<boolean> {
    const written = (name: string, of: Record<string, number>) =>
      `export const ${name} = { ${Object.entries(of)
        .map(([key, value]) => `${key}: ${String(Number(value.toFixed(3)))}`)
        .join(", ")} };`;
    const text = [written("sea", sea), written("wave", wave)].join("\n");
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      console.warn(text);
      return false;
    }
  }
}
