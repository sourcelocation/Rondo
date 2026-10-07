import { createMDX } from "fumadocs-mdx/next";
import type { NextConfig } from "next";

/** The docs, exported as static files that the site image serves at rondo.matthewsource.com/docs. */
const config: NextConfig = {
  output: "export",
  basePath: "/docs",
  reactStrictMode: true,
};

export default createMDX()(config);
