import type { Flashcard } from "./cards";

/**
 * The AI section: what's asked, in the words that finish the headline ("Make your cards …"), and a hand of the cards
 * that come of it (motion/assistant.ts).
 */

/** The headline's ending as it rests, before anything is asked. */
export const resting = "with AI.";

/** One request: the words that finish the headline, and five of the cards that come of it. */
export interface Request {
  readonly words: string;
  readonly cards: readonly Flashcard[];
}

export const requests: readonly Request[] = [
  {
    words: "from my biology notes.",
    cards: [
      { deck: "Biology", front: "Ribosome", back: "Builds proteins from RNA" },
      { deck: "Biology", front: "Nucleus", back: "Holds the cell’s DNA" },
      { deck: "Biology", front: "Mitochondria", back: "Where cells make ATP" },
      { deck: "Biology", front: "Golgi body", back: "Packs proteins to ship" },
      { deck: "Biology", front: "Lysosome", back: "Breaks down waste" },
    ],
  },
  {
    words: "with example sentences.",
    cards: [
      { deck: "Japanese › Verbs", front: "食べる", back: "朝ごはんを食べる。", detail: "I eat breakfast.", lang: "ja" },
      { deck: "Japanese › Verbs", front: "飲む", back: "コーヒーを飲む。", detail: "I drink coffee.", lang: "ja" },
      { deck: "Japanese › Verbs", front: "覚える", back: "単語を覚える。", detail: "I learn the words.", lang: "ja" },
      { deck: "Japanese › Verbs", front: "行く", back: "図書館に行く。", detail: "I go to the library.", lang: "ja" },
      { deck: "Japanese › Verbs", front: "読む", back: "本を読む。", detail: "I read a book.", lang: "ja" },
    ],
  },
  {
    words: "on the French Revolution.",
    cards: [
      { deck: "History", front: "1789", back: "The Bastille falls" },
      { deck: "History", front: "Varennes", back: "The king’s flight fails" },
      { deck: "History", front: "Robespierre", back: "Leads the Terror" },
      { deck: "History", front: "Thermidor", back: "Robespierre falls" },
      { deck: "History", front: "1799", back: "Napoleon takes power" },
    ],
  },
  {
    words: "on Europe’s capitals.",
    cards: [
      { deck: "Geography", front: "Portugal", back: "Lisbon" },
      { deck: "Geography", front: "Norway", back: "Oslo" },
      { deck: "Geography", front: "Hungary", back: "Budapest" },
      { deck: "Geography", front: "Estonia", back: "Tallinn" },
      { deck: "Geography", front: "Greece", back: "Athens" },
    ],
  },
];
