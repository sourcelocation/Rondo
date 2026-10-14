import { markLean } from "../brand";
import { marks, plot, recall, reviews } from "../content/history";
import { Stack } from "../motion/stack";

/**
 * /places.css, which every page links: where the drawings put what's on them, worked out as the site is built. The
 * Anki section's memory places each review, the name of each gap, the seam and now on its plot (components/Anki.astro),
 * the collection's still deck sizes and shades each card by how far back it stands (components/Collection.astro), and
 * the intro sets Rondo's mark off its middle (components/Intro.astro). Each component's styles place things by the
 * custom properties set here. It's a file of its own because the site's CSP allows no inline styles, style attributes
 * included.
 */

/** A place on the memory's plot, as the share of its width and height that CSS positions things by. */
const at = (selector: string, x: number, y: number = plot.target) =>
  `${selector} { --x: ${(x / plot.width).toFixed(4)}; --y: ${(y / plot.height).toFixed(4)}; }`;

const stack = new Stack();

const rules = [
  at("[data-memory-wash]", marks.seam),
  at("[data-memory-seam]", marks.seam, recall(marks.seam)),
  at("[data-memory-head]", marks.now, recall(marks.now)),
  at("[data-memory-next]", marks.next),
  ...reviews.flatMap((review) => [
    at(`[data-memory-review="${review.x}"]`, review.x),
    at(`[data-memory-gap="${review.x}"]`, review.labelX),
  ]),
  `[data-pile] { --horizon: ${stack.horizon}; }`,
  ...Array.from(
    { length: stack.depth + 1 },
    (_, depth) =>
      `[data-pile-card="${depth}"] { --scale: ${stack.scale(depth).toFixed(5)}; --shade: ${stack.shade(depth).toFixed(4)}; opacity: ${stack.presence(depth)}; }`,
  ),
  `[data-intro-mark] { --lean: ${markLean}; }`,
];

export const GET = (): Response =>
  new Response(`${rules.join("\n")}\n`, { headers: { "Content-Type": "text/css; charset=utf-8" } });
