import { all } from "./dom";
import type { Stage } from "./stage";

/** Brings one element's scene to life, given the element and the page's stage. */
export type Mount = (root: HTMLElement, stage: Stage) => void;

const registered: { selector: string; mount: Mount }[] = [];
let ready: Stage | null = null;

/**
 * Declares a scene: `mount` brings to life every element `selector` names, once the page's stage is ready. Each
 * component that moves declares its own in its script, beside its markup, so a page is made of whatever components
 * it uses and nothing has to list them. A scene that fails leaves the others running.
 */
export function scene(selector: string, mount: Mount): void {
  if (ready == null) registered.push({ selector, mount });
  else start(selector, mount, ready);
}

/** Mounts every scene declared so far, and from then on each as it's declared. */
export function mountScenes(stage: Stage): void {
  ready = stage;
  for (const { selector, mount } of registered.splice(0)) start(selector, mount, stage);
}

function start(selector: string, mount: Mount, stage: Stage): void {
  for (const root of all(document, selector)) {
    try {
      mount(root, stage);
    } catch (error) {
      console.error(error);
    }
  }
}
