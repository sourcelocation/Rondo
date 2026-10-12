/**
 * The AI assistants section: what it shows an assistant doing (motion/assistant.ts), which is enriching cards
 * someone already has, and the other things anyone can ask one for.
 */

/** The assistant the conversation is with (the others are only named). */
export const assistantName = "Claude";

/** What's asked, how the assistant goes about it, and what it says when it's done. */
export const conversation = {
  ask: "Add an example sentence and audio to every card in Japanese › Verbs.",
  search: { tool: "search_notes", found: "48 notes in Japanese › Verbs" },
  update: { tool: "update_notes", total: 48 },
  reply: "Done: all 48 cards have an example sentence and audio now. Want readings for the kanji next?",
} as const;

/** A card the assistant enriches: what it had, and what it gets. */
export interface Enrichment {
  readonly word: string;
  readonly reading: string;
  readonly meaning: string;
  readonly example: string;
  /** The example with the card's word marked out, as `[before, word, after]`. */
  readonly marked: readonly [string, string, string];
  readonly gloss: string;
  readonly seconds: number;
}

export const enriched: readonly Enrichment[] = [
  {
    word: "食べる",
    reading: "たべる",
    meaning: "to eat",
    example: "朝ごはんを食べる。",
    marked: ["朝ごはんを", "食べる", "。"],
    gloss: "I eat breakfast.",
    seconds: 2,
  },
  {
    word: "飲む",
    reading: "のむ",
    meaning: "to drink",
    example: "毎朝コーヒーを飲む。",
    marked: ["毎朝コーヒーを", "飲む", "。"],
    gloss: "I drink coffee every morning.",
    seconds: 3,
  },
  {
    word: "行く",
    reading: "いく",
    meaning: "to go",
    example: "明日、図書館に行く。",
    marked: ["明日、図書館に", "行く", "。"],
    gloss: "I’m going to the library tomorrow.",
    seconds: 2,
  },
];

/** Everything else to ask for, as someone might put it. */
export const requests: readonly string[] = [
  "Make a deck from this article",
  "Turn my notes into cloze cards",
  "What’s due today?",
];
