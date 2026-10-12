import type { ImageMetadata } from "astro";
import geoduels from "../assets/work/geoduels.webp";
import picasso from "../assets/work/picasso.webp";

/** Something Rondo's maker built before, as the maker section lists it. */
export interface Project {
  readonly name: string;
  readonly summary: string;
  /** How many people have used it. */
  readonly users: number;
  /** The year it came out. */
  readonly year: number;
  readonly url: string;
  readonly image: ImageMetadata;
  readonly alt: string;
}

export const projects: readonly Project[] = [
  {
    name: "GeoDuels",
    summary: "Real-time geography duels, free and ad-free",
    users: 350_000,
    year: 2026,
    url: "https://geoduels.io",
    image: geoduels,
    alt: "GeoDuels' play screen: ranked duels, singleplayer and trending maps",
  },
  {
    name: "Picasso",
    summary: "Reverse-engineering iOS to unlock what Apple locks away",
    users: 100_000,
    year: 2023,
    url: "https://github.com/sourcelocation/Picasso-v3",
    image: picasso,
    alt: "A disassembler patching a SpringBoard function beside Picasso's tweak builder on an iPhone",
  },
];
