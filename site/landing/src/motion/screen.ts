import { one } from "./dom";

/**
 * The screen, as everything that moves measures it: its width, and its height two ways, both steady while a phone's
 * toolbars come and go. `height` is the screen with them away (--screen): what the scenery, the film and the curtain
 * fill, the toolbars passing over them, and what ScrollTrigger measures by. `safe` is the screen with them out
 * (--screen-safe): what's always seen, where anything that has to be seen is framed. Both are measured from the page's
 * probe (layouts/Page.astro), laid out at each, so they're exactly what CSS has; they change only when the screen
 * really does (a phone turned, a window resized), never as the toolbars move. Nothing reads the window's own height,
 * which does.
 */
export class Screen {
  width = 1;
  height = 1;
  safe = 1;
  private readonly inner: HTMLElement;
  private readonly changes: (() => void)[] = [];

  constructor(private readonly probe: HTMLElement) {
    this.inner = one(probe, "[data-screen-safe]");
    this.measure();
    const observer = new ResizeObserver(() => {
      if (!this.measure()) return;
      for (const change of this.changes) change();
    });
    observer.observe(probe);
    observer.observe(this.inner);
  }

  /** Calls `change` whenever the screen changes size, once it's been measured anew. */
  onChange(change: () => void): void {
    this.changes.push(change);
  }

  /** Measures the probe again: whether the screen has changed. */
  private measure(): boolean {
    const box = this.probe.getBoundingClientRect();
    const width = Math.max(box.width, 1);
    const height = Math.max(box.height, 1);
    const safe = Math.max(Math.min(this.inner.getBoundingClientRect().height, height), 1);
    if (width === this.width && height === this.height && safe === this.safe) return false;
    this.width = width;
    this.height = height;
    this.safe = safe;
    return true;
  }
}
