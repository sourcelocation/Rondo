import type { Brand } from "../brands";

/**
 * What the three devices in the native-apps section study, each a deck of its own and a kind of card Rondo draws
 * natively: Japanese with furigana on the phone, cloze on the tablet, and maths on the laptop (written out in
 * Platforms.astro, being markup). Each device goes through its cards in turn.
 */
export const phoneCards = [
  { word: "記憶", reading: "きおく", meaning: "memory" },
  { word: "時間", reading: "じかん", meaning: "time" },
  { word: "言葉", reading: "ことば", meaning: "word" },
] as const;

export const tabletCards = [
  { before: "The", answer: "mitral", after: "valve separates the left atrium from the left ventricle." },
  { before: "Blood leaves the left ventricle through the", answer: "aortic", after: "valve." },
  { before: "The", answer: "sinoatrial", after: "node sets the heart’s rhythm." },
] as const;

/** The platforms each device stands for, named under it. */
export const devicePlatforms = {
  phone: [
    { name: "iOS", brand: "apple" },
    { name: "Android", brand: "android" },
  ],
  tablet: [
    { name: "iPadOS", brand: "apple" },
    { name: "Android", brand: "android" },
  ],
  laptop: [
    { name: "macOS", brand: "apple" },
    { name: "Windows", brand: "windows" },
    { name: "Linux", brand: "linux" },
    { name: "Web", brand: "web" },
  ],
} as const satisfies Record<string, readonly { name: string; brand: Brand }[]>;

export type Gadget = keyof typeof devicePlatforms;

/** The platform each device is drawn as (its data-os): a mix, so the picture shows more than one. */
export const shown: Record<Gadget, string> = { phone: "ios", tablet: "android", laptop: "macos" };
