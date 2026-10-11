/**
 * The site's own pages, which open through the curtain (motion/transition.ts), and what the curtain says on the way
 * to each. The docs and the web app are other sites under the same host, and open as any link does.
 */
export const pages: Readonly<Record<string, string>> = {
  "/": "Rondo",
  "/download": "Download",
  "/press": "Press & brand",
  "/terms": "Terms",
  "/privacy": "Privacy",
};

/** A path as `pages` has it: without a trailing slash, except the home page's. */
export function pagePath(path: string): string {
  return path.replace(/\/+$/u, "") || "/";
}
