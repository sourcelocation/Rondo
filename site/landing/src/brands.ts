import { mdiMicrosoft, mdiWeb } from "@mdi/js";
import { siAndroid, siApple, siDiscord, siGithub, siLinux, siX } from "simple-icons";

/**
 * Other companies' marks, where the site names their platforms or links to them: Simple Icons' (CC0) for most, and
 * Material Design Icons' (Apache-2.0) for Windows (Simple Icons has none) and the web. Each viewBox crops its icon to
 * fill the same square. They're drawn once per page, in the sprite (Sprite.astro).
 */
export const brands = {
  apple: { path: siApple.path, viewBox: "0 0 24 24" },
  android: { path: siAndroid.path, viewBox: "0 0 24 24" },
  windows: { path: mdiMicrosoft, viewBox: "2 3 19 19" },
  linux: { path: siLinux.path, viewBox: "0 0 24 24" },
  web: { path: mdiWeb, viewBox: "2 2 20 20" },
  github: { path: siGithub.path, viewBox: "0 0 24 24" },
  discord: { path: siDiscord.path, viewBox: "0 0 24 24" },
  x: { path: siX.path, viewBox: "0 0 24 24" },
} as const;

export type Brand = keyof typeof brands;

/** The platforms Rondo runs on, as /launch.js names the visitor's. */
export type Os = "ios" | "ipados" | "android" | "macos" | "windows" | "linux" | "web";

/** Each platform's mark. */
export const osBrand: Record<Os, Brand> = {
  ios: "apple",
  ipados: "apple",
  macos: "apple",
  android: "android",
  windows: "windows",
  linux: "linux",
  web: "web",
};
