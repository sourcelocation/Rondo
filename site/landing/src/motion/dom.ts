/** A kind of element, by its constructor: HTMLButtonElement, SVGPathElement… */
type Kind<T extends Element> = abstract new (...args: never[]) => T;

/** The element under `root` that `selector` names, which the page's markup always has, of the kind given. */
export function one<T extends Element>(root: ParentNode, selector: string, kind: Kind<T>): T;
export function one(root: ParentNode, selector: string): HTMLElement;
export function one(root: ParentNode, selector: string, kind: Kind<Element> = HTMLElement): Element {
  const found = root.querySelector(selector);
  if (!(found instanceof kind)) throw new Error(`The page has no ${selector} of the expected kind`);
  return found;
}

/** Every element under `root` that `selector` names, of the kind given, in document order. */
export function all<T extends Element>(root: ParentNode, selector: string, kind: Kind<T>): T[];
export function all(root: ParentNode, selector: string): HTMLElement[];
export function all(root: ParentNode, selector: string, kind: Kind<Element> = HTMLElement): Element[] {
  return [...root.querySelectorAll(selector)].filter((element) => element instanceof kind);
}

/** Whether the visitor asked for less motion: the page then shows everything in place and moves only on request. */
export function prefersReducedMotion(): boolean {
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/** Whether the visitor points with a mouse or a trackpad, which can hover. */
export function hasFinePointer(): boolean {
  return window.matchMedia("(hover: hover) and (pointer: fine)").matches;
}
