import type { ImageMetadata } from "astro";
import geoduels from "../assets/work/geoduels.webp";
import picasso from "../assets/work/picasso-cowabunga.webp";

/** Something Rondo's maker built before, as the maker section lists it. */
export interface Project {
  readonly name: string;
  readonly summary: string;
  readonly facts: readonly string[];
  readonly url: string;
  readonly image: ImageMetadata;
  readonly alt: string;
}

export const projects: readonly Project[] = [
  {
    name: "GeoDuels",
    summary: "Real-time geography duels, free and ad-free",
    facts: ["232,000+ plays", "100,000 locations", "55 releases in 20 weeks"],
    url: "https://geoduels.io",
    image: geoduels,
    alt: "GeoDuels' play screen: ranked duels, singleplayer and trending maps",
  },
  {
    name: "Picasso & Cowabunga",
    summary: "iPhone customization without a jailbreak",
    facts: ["518,000+ downloads", "44 releases in 21 weeks"],
    url: "https://github.com/sourcelocation/Picasso-v3",
    image: picasso,
    alt: "Cowabunga on three iPhones: a lock screen, its tweak options and a passcode face editor",
  },
];
