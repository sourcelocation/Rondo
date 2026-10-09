import { PUBLIC_RONDO_APP_URL, PUBLIC_RONDO_DOCS_URL } from "astro:env/client";

/** Where the site sends people. */
export const links = {
  app: PUBLIC_RONDO_APP_URL,
  docs: PUBLIC_RONDO_DOCS_URL,
  source: "https://github.com/sourcelocation/Rondo",
  license: "https://github.com/sourcelocation/Rondo/blob/HEAD/LICENSE",
  architecture: "https://github.com/sourcelocation/Rondo/blob/HEAD/ARCHITECTURE.md#data",
  discord: "https://discord.gg/GfftwYzbdV",
  /** The maker, where they post. */
  x: "https://x.com/sourceloc",
} as const;
