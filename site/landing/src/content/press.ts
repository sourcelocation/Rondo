import tokens from "../../../../brand/dist/tokens.json";
import { LAUNCH } from "../launch/launch";
import css from "../styles/base.css?raw";
import { links } from "./links";

/**
 * The press kit (pages/press): what Rondo is, in a sentence and in a paragraph; the facts; and how the
 * brand looks and moves. Colours come from where they're defined (the brand's tokens, the site's own styles), so
 * the page can't drift from them.
 */

const launch = new Intl.DateTimeFormat("en", { dateStyle: "long", timeZone: "Etc/GMT-3" }).format(LAUNCH);

/** What Rondo is, for a writer to quote. The launch is told before and after it happens (`pre`, `live`). */
export const about = {
  sentence:
    "Rondo is a free, open-source flashcard app with native apps on every platform, sync that works offline, and everything people rely on in Anki, review history included.",
  paragraph: [
    "Rondo is a free, open-source flashcard app for iPhone, iPad, Android, Mac, Windows, Linux and the web.",
    "It schedules reviews to fit how each learner remembers, draws cloze, image occlusion, maths, furigana and pinyin natively, and imports Anki decks with their media and full review history.",
    "Every device keeps the whole collection and works offline; changes sync in the background, and answers from devices that were offline at the same time all count.",
    "AI assistants such as Claude and ChatGPT can enrich a learner’s cards or make new ones, and anyone can run a Rondo server of their own.",
  ].join(" "),
  launch: {
    pre: `Rondo is made by sourcelocation, and opens to everyone on ${launch}.`,
    live: `Rondo is made by sourcelocation, and opened to everyone on ${launch}.`,
  },
} as const;

/** The facts, at a glance. */
export const facts: readonly { readonly term: string; readonly value: string; readonly href?: string }[] = [
  { term: "Opens", value: launch },
  { term: "Platforms", value: "iPhone, iPad, Android, Mac, Windows, Linux and the web" },
  { term: "Price", value: "Free. Pro adds editors on shared decks, and 10 GB for pictures and sounds" },
  { term: "Licence", value: "Open source, AGPL-3.0", href: links.license },
  { term: "Made by", value: "sourcelocation", href: links.x },
  { term: "Website", value: "rondo.matthewsource.com", href: "https://rondo.matthewsource.com" },
  { term: "Source", value: "github.com/sourcelocation/Rondo", href: links.source },
];

/** A colour of the brand's: its name, its value, and what it's for. */
export interface Swatch {
  readonly name: string;
  readonly hex: string;
  readonly use?: string;
  /** Text drawn on it reads in white. */
  readonly dark?: boolean;
}

/** A brand token's light value (brand/dist/tokens.json). */
function token(name: string): string {
  const found = tokens.colors.find((colour) => colour.name === name);
  if (found == null) throw new Error(`The brand has no colour ${name}`);
  return found.light.toUpperCase();
}

/** A colour the site defines for itself (styles/base.css). */
function site(name: string): string {
  const found = new RegExp(`--${name}:\\s*(#[0-9a-f]{3,8})`, "iu").exec(css)?.[1];
  if (found == null) throw new Error(`The site's styles have no --${name}`);
  return found.toUpperCase();
}

export const palette: readonly {
  readonly title: string;
  readonly note: string;
  readonly swatches: readonly Swatch[];
}[] = [
  {
    title: "The brand",
    note: "The mark is one colour: ink on paper, and paper on ink. The accent is the product’s, never the mark’s.",
    swatches: [
      { name: "Ink", hex: token("brand.ink"), use: "The mark and the words", dark: true },
      { name: "Paper", hex: token("brand.paper"), use: "What the mark sits on" },
      { name: "Accent", hex: token("brand.accent"), use: "What’s new, links, clozes", dark: true },
    ],
  },
  {
    title: "The light",
    note: "Four pastels, always soft and always together: the light behind the site.",
    swatches: [
      { name: "Sky", hex: site("sky") },
      { name: "Lilac", hex: site("lilac") },
      { name: "Peach", hex: site("peach") },
      { name: "Mint", hex: site("mint") },
      { name: "Signal", hex: site("signal"), dark: true },
    ],
  },
];

/** The brand's typefaces (brand/fonts), each with what it's for and a line to show it with. */
export const typefaces = [
  {
    name: "Juana",
    role: "Display, card prompts and the wordmark",
    weights: "Light to Bold, with italics",
    sample: "Remember for good.",
    family: "serif",
  },
  {
    name: "Geist",
    role: "The interface, and everything you read",
    weights: "Light to Bold",
    sample: "Every device keeps your whole collection.",
    family: "sans",
  },
  {
    name: "Geist Mono",
    role: "Code",
    weights: "Regular and Medium",
    sample: "rondo-mcp sign-in",
    family: "mono",
  },
] as const;

/** The brand's motion: its curves, as styles/base.css defines them, and what each is for. */
export const curves = [
  { name: "Out", variable: "--ease-out", use: "Arriving: quick, then settling", path: bezier(0.16, 1, 0.3, 1) },
  {
    name: "In and out",
    variable: "--ease-in-out",
    use: "Going from one place to another",
    path: bezier(0.76, 0, 0.24, 1),
  },
  { name: "Spring", variable: "--ease-spring", use: "Landing, with a little give", path: spring(0.8, 9.5) },
] as const;

/** A cubic Bézier easing as a path across a 100 by 100 box, the end at the top right. */
function bezier(x1: number, y1: number, x2: number, y2: number): string {
  return `M 0 100 C ${x1 * 100} ${100 - y1 * 100}, ${x2 * 100} ${100 - y2 * 100}, 100 0`;
}

/** A damped spring's settling over a second, as a path across the same box: it overshoots, and comes back. */
function spring(damping: number, frequency: number): string {
  const decay = damping * frequency;
  const ringing = frequency * Math.sqrt(1 - damping * damping);
  const points = Array.from({ length: 61 }, (_, index) => {
    const t = index / 60;
    const value = 1 - Math.exp(-decay * t) * (Math.cos(ringing * t) + (decay / ringing) * Math.sin(ringing * t));
    return `${(t * 100).toFixed(2)} ${(100 - value * 100).toFixed(2)}`;
  });
  return `M ${points.join(" L ")}`;
}

/** The screenshots the kit will have, by device and screen: placeholders until the launch. */
export const screenshots = [
  { device: "iPhone", screen: "Studying", shape: "phone" },
  { device: "Mac", screen: "Today", shape: "wide" },
  { device: "Android", screen: "Discover", shape: "phone" },
  { device: "iPad", screen: "Browse", shape: "tablet" },
  { device: "Windows", screen: "A deck", shape: "wide" },
  { device: "Web", screen: "Importing from Anki", shape: "wide" },
] as const;
