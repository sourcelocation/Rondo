/** A flashcard, as the hero's ring and the collection section show one. */
export interface Flashcard {
  readonly deck: string;
  readonly front: string;
  readonly back: string;
  /** A reading or a note under the answer. */
  readonly detail?: string;
  /** The language of the front and the answer, when it isn't English. */
  readonly lang?: string;
}

/** The cards circling the headline. */
export const ring: readonly Flashcard[] = [
  { deck: "Japanese", front: "記憶", back: "memory", detail: "きおく", lang: "ja" },
  { deck: "Cardiology", front: "Mitral valve", back: "Left atrium to left ventricle" },
  { deck: "Spanish", front: "recordar", back: "to remember", lang: "es" },
  { deck: "Calculus", front: "∫ x² dx", back: "x³⁄3 + C" },
  { deck: "Geography", front: "Mongolia", back: "Ulaanbaatar", detail: "Capital" },
  { deck: "History", front: "1789", back: "The French Revolution begins" },
  { deck: "Music", front: "Rondo", back: "A B A C A D A", detail: "The theme keeps returning" },
  { deck: "Biology", front: "Mitochondria", back: "Where cells make ATP" },
  { deck: "German", front: "die Erinnerung", back: "memory", lang: "de" },
  { deck: "Chemistry", front: "Avogadro", back: "6.022 × 10²³ per mole" },
  { deck: "Korean", front: "기억", back: "memory", detail: "gieok", lang: "ko" },
  { deck: "Astronomy", front: "Betelgeuse", back: "α Orionis", detail: "Red supergiant" },
];

/** The cards the collection section stacks, from collections of every kind, in turn. */
export const dealt: readonly Flashcard[] = [
  { deck: "Japanese", front: "記憶", back: "きおく · memory", lang: "ja" },
  { deck: "Cardiology", front: "Mitral valve", back: "Left atrium to left ventricle" },
  { deck: "Spanish", front: "recordar", back: "to remember", lang: "es" },
  { deck: "Calculus", front: "∫ x² dx", back: "x³⁄3 + C" },
  { deck: "Geography", front: "Mongolia", back: "Ulaanbaatar" },
  { deck: "History", front: "1789", back: "The French Revolution begins" },
  { deck: "Biology", front: "Mitochondria", back: "Where cells make ATP" },
  { deck: "German", front: "die Erinnerung", back: "memory", lang: "de" },
  { deck: "Chemistry", front: "Avogadro", back: "6.022 × 10²³ per mole" },
  { deck: "Korean", front: "기억", back: "gieok · memory", lang: "ko" },
  { deck: "Astronomy", front: "Betelgeuse", back: "α Orionis" },
  { deck: "French", front: "se souvenir", back: "to remember", lang: "fr" },
  { deck: "Anatomy", front: "Femur", back: "The longest bone in the body" },
  { deck: "Law", front: "Habeas corpus", back: "That you have the body" },
  { deck: "Physics", front: "E = mc²", back: "Mass–energy equivalence" },
  { deck: "Mandarin", front: "记忆", back: "jìyì · memory", lang: "zh" },
  { deck: "Art", front: "Chiaroscuro", back: "Light set hard against dark" },
  { deck: "Pharmacology", front: "Metoprolol", back: "A β₁-selective blocker" },
  { deck: "Italian", front: "ricordare", back: "to remember", lang: "it" },
  { deck: "Literature", front: "1922", back: "Ulysses, and The Waste Land" },
  { deck: "Algorithms", front: "Merge sort", back: "O(n log n)" },
  { deck: "Russian", front: "память", back: "pamyat · memory", lang: "ru" },
  { deck: "Economics", front: "Elasticity", back: "How demand answers price" },
];

/** The card the collection's stacking ends on, which stays in front. */
export const finale: Flashcard = { deck: "Music", front: "Rondo", back: "A B A C A D A" };

/** How many cards the collection holds once it's stacked: a big collection, as people bring them. */
export const collection = 48_213;

/** The study screen on the laptop in the flight (motion/flight/study.ts): its words, and the cards studied, in turn. */
export const studying = {
  session: "Today",
  due: "due",
  show: "Show answer",
  ratings: ["Again", "Hard", "Good", "Easy"],
  offline: "Offline",
  cards: [
    { deck: "Spanish", front: "recordar", back: "to remember", lang: "es" },
    { deck: "Biology", front: "Mitochondria", back: "Where cells make ATP" },
    { deck: "Law", front: "Habeas corpus", back: "That you have the body" },
    { deck: "Art", front: "Chiaroscuro", back: "Light set hard against dark" },
    { deck: "French", front: "se souvenir", back: "to remember", lang: "fr" },
    { deck: "Geography", front: "Mongolia", back: "Ulaanbaatar" },
    { deck: "Anatomy", front: "Femur", back: "The longest bone in the body" },
    { deck: "History", front: "1789", back: "The French Revolution begins" },
  ] satisfies readonly Flashcard[],
} as const;
