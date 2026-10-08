/** A flashcard as the hero's ring shows one. */
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
