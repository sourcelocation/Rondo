import { fileURLToPath } from "node:url";
import { createMDX } from "fumadocs-mdx/next";
import type { NextConfig } from "next";

/** The docs, exported as static files that the site image serves at rondo.matthewsource.com/docs. */
const config: NextConfig = {
  output: "export",
  basePath: "/docs",
  reactStrictMode: true,
  // The brand's fonts live beside the site, in the repository's brand/.
  turbopack: { root: fileURLToPath(new URL("../..", import.meta.url)) },
};

export default createMDX()(config);
