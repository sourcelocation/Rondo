import { links } from "./links";

/** A question people ask before trying Rondo: its answer, in paragraphs, and where the docs say more. */
export interface Question {
  readonly question: string;
  readonly answer: readonly string[];
  readonly more?: { readonly label: string; readonly href: string };
}

/** The landing page's questions, answered as the docs and the architecture have it, and gladly. */
export const faq: readonly Question[] = [
  {
    question: "Why would I switch from Anki?",
    answer: [
      "Because you keep everything and lose the friction. One import brings your decks, media and every past review, and FSRS-6, fitted to you, schedules them from there.",
      "Then it gets better: native apps on every device that open tens of thousands of cards without a wait, sync that never asks you to upload or download, and AI assistants that make the cards you already have richer. All of it free.",
    ],
    more: { label: "Importing from Anki", href: `${links.docs}importing` },
  },
  {
    question: "What’s free, and what does Pro add?",
    answer: [
      "Everything you need to study is free, on every device, for good: sync, Anki import, sharing, Discover and AI assistants. No trial that runs out, and nothing about studying held back.",
      "Pro adds two things for people who need them: others editing the decks you share, and 10 GB for pictures and sounds instead of 200 MB.",
    ],
    more: { label: "Free and Pro", href: `${links.docs}plans` },
  },
  {
    question: "How is Rondo paid for?",
    answer: [
      "By Pro, and nothing else. People who want editors on the decks they share, or room for lots of pictures and sounds, subscribe monthly or yearly, on the web or through the App Store or Google Play. That pays for the servers and the work, so studying never has to.",
    ],
  },
  {
    question: "Does it have tags? And add-ons?",
    answer: [
      "Tags, absolutely. They come over from Anki just as they were, nested with ::, and Browse filters by them, a tag standing for every tag under it.",
      "As for add-ons: what people install them for most is already built in. Image occlusion, furigana and pinyin, LaTeX, typed answers and text-to-speech all come with Rondo, and the API and any AI assistant you like cover much of the rest. (Anki’s own add-ons are Python written for its desktop app, so they don’t run here.)",
    ],
    more: { label: "Finding notes by tag", href: `${links.docs}search` },
  },
  {
    question: "Can I go back to Anki?",
    answer: [
      "Any time, so trying Rondo costs you nothing. It imports a package you export from Anki and never touches your collection, so keep both side by side for as long as you like.",
      "And everything you make in Rondo is yours to take: Settings › Account › Export everything sends you a ZIP of your decks, notes, review history and media.",
    ],
    more: { label: "Importing from Anki", href: `${links.docs}importing` },
  },
  {
    question: "Can I run my own server?",
    answer: [
      "Yes, and it’s one file. Rondo’s servers are open source, and a single Docker Compose file runs all of them behind Caddy, which gets its certificate itself. Bring a Linux server on x86-64, a hostname, and an SMTP login to send sign-in codes.",
      "The apps, the web app and AI assistants all work with it, just as they do with Rondo’s.",
    ],
    more: { label: "Run your own server", href: `${links.docs}self-hosting` },
  },
  {
    question: "Where does my data live?",
    answer: [
      "On your devices, first and always. Each keeps your whole collection in its own database (the web app’s, in your browser), so everything works offline, and without an account nothing leaves the device at all.",
      "Sign in and it syncs through Rondo’s server, pictures and sounds in object storage. Or through a server of your own, and it lives there instead.",
    ],
    more: { label: "Sync and offline", href: `${links.docs}sync` },
  },
];
