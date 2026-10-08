import type { Brand, Os } from "../brands";
import { links } from "./links";

/** Where an app is got: a store, a download, or the web. A `url` of null isn't ready, and says "Coming soon". */
export interface Channel {
  readonly label: string;
  readonly url: string | null;
}

/** A platform on the download page: its mark, its name, a line about it, and where to get it. */
export interface Download {
  readonly id: string;
  readonly brand: Brand;
  readonly name: string;
  readonly line: string;
  readonly channels: readonly Channel[];
  /** The visitor's platforms (data-os) it's for. */
  readonly for: readonly Os[];
}

/**
 * Every platform Rondo is on. The stores' links come with the launch: until then they're null, and the page says
 * "Coming soon" where they'll go.
 */
export const downloads: readonly Download[] = [
  {
    id: "apple-mobile",
    brand: "apple",
    name: "iPhone & iPad",
    line: "Study anywhere, and spread out on the big screen.",
    channels: [{ label: "App Store", url: null }],
    for: ["ios", "ipados"],
  },
  {
    id: "android",
    brand: "android",
    name: "Android",
    line: "For phones and tablets alike.",
    channels: [{ label: "Google Play", url: null }],
    for: ["android"],
  },
  {
    id: "mac",
    brand: "apple",
    name: "Mac",
    line: "A native app for macOS.",
    channels: [{ label: "App Store", url: null }],
    for: ["macos"],
  },
  {
    id: "windows",
    brand: "windows",
    name: "Windows",
    line: "A native app for Windows.",
    channels: [{ label: "Download", url: null }],
    for: ["windows"],
  },
  {
    id: "linux",
    brand: "linux",
    name: "Linux",
    line: "A native app for Linux.",
    channels: [{ label: "Download", url: null }],
    for: ["linux"],
  },
  {
    id: "web",
    brand: "web",
    name: "Web",
    line: "In any modern browser, offline too.",
    channels: [{ label: "Open Rondo", url: links.app }],
    for: ["web"],
  },
];

/** The marks that circle Rondo's at the top of the page, and what each stands for. */
export const orbit: readonly { brand: Brand; label: string; for: readonly Os[] }[] = [
  { brand: "apple", label: "iPhone, iPad & Mac", for: ["ios", "ipados", "macos"] },
  { brand: "android", label: "Android", for: ["android"] },
  { brand: "windows", label: "Windows", for: ["windows"] },
  { brand: "linux", label: "Linux", for: ["linux"] },
  { brand: "web", label: "Web", for: ["web"] },
];
