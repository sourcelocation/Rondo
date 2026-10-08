import logo from "../../../brand/dist/svg/logo.svg?raw";

/**
 * The brand's logo (brand/dist/svg/logo.svg) as the page uses it: one copy of the artwork in an inline sprite
 * (Sprite.astro), which every mark and wordmark on the page references and crops.
 */

/** The artwork's id in the sprite. */
export const ART = "rondo-art";

/** The logo's content without its document wrapper and ids, painted in the text colour. */
export const artwork: string = logo
  .replace(/^[\s\S]*?<svg[^>]*>/u, "")
  .replace(/<\/svg>\s*$/u, "")
  .replace(/<title>[\s\S]*?<\/title>/u, "")
  .replace(/\s+id="[^"]*"/gu, "")
  .replaceAll('fill="#000000"', 'fill="currentColor"');

/**
 * Where the logo sits on its 1534 × 1536 artboard, as viewBoxes: whole, and in its two parts, the mark (a bookmark
 * with an arrow turning back through it) and the wordmark beside it. The parts meet halfway between the mark's arrow
 * and the "R", so the two crops side by side at one height draw the whole logo.
 */
const top = 620;
const height = 302;
const left = 151;
const seam = 444;
const right = 1455;

export const crops = {
  logo: `${left} ${top} ${right - left} ${height}`,
  mark: `${left} ${top} ${seam - left} ${height}`,
  word: `${seam} ${top} ${right - seam} ${height}`,
} as const;

/** The whole logo's width for a height of 1. */
export const logoAspect = (right - left) / height;
