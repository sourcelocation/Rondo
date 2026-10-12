import type { Stage } from "./stage";

/**
 * The collection section's deck. The scenery stacks it (motion/scenery/pile.ts), cards coming to it from beyond the
 * screen's top corners faster and faster as the count beside climbs into infinity, the deck following a mouse along.
 * Without the scenery (keeping still, or without WebGL), the page's still deck stands there, stacked, beside infinity.
 */
export function pile(root: HTMLElement, stage: Stage): void {
  const stacked = stage.scenery.then(async (scenery) => {
    if (scenery == null) {
      root.closest("section")?.classList.add("is-still");
      return;
    }
    const { Pile } = await import("./scenery/pile");
    await new Pile(root, scenery, stage).ready;
  });
  stage.prepare(stacked);
}
