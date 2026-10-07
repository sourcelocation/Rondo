import type { Config } from "prettier";

/** How every TypeScript, CSS, JSON, MDX and Astro file of the websites is laid out. */
const config: Config = {
  // .editorconfig's limit.
  printWidth: 120,
  plugins: ["prettier-plugin-astro"],
};

export default config;
