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

/** Each device's platforms, the first being where it starts. */
export const devicePlatforms = {
  phone: [
    { os: "android", name: "Android", brand: "android" },
    { os: "ios", name: "iOS", brand: "apple" },
  ],
  tablet: [
    { os: "ipados", name: "iPadOS", brand: "apple" },
    { os: "android", name: "Android", brand: "android" },
  ],
  laptop: [
    { os: "windows", name: "Windows", brand: "windows" },
    { os: "linux", name: "Linux", brand: "linux" },
    { os: "web", name: "Web", brand: "web" },
    { os: "macos", name: "macOS", brand: "apple" },
  ],
} as const;
