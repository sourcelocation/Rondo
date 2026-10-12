import { gsap } from "gsap";
import { SplitText } from "gsap/SplitText";
import { conversation } from "../content/assistants";
import { all, one } from "./dom";
import { loopInView } from "./loop";
import type { Stage } from "./stage";

/** A card the assistant works on: the card, the light it passes over it, and what it gains. */
interface Note {
  readonly element: HTMLElement;
  readonly scan: HTMLElement;
  readonly more: HTMLElement;
  readonly inner: Element[];
  readonly bars: HTMLElement[];
  /** Each bar's height, as the styles draw the recording. */
  readonly heights: number[];
}

/** When each card is worked on, in seconds into the story. */
const working = [4.3, 5.5, 6.7];

/**
 * The AI section's stage, told on a loop while it's in view. The request is
 * typed and sent; the assistant thinks, then looks through the decks (its search tool spinning, then ticked) and
 * starts writing to the notes, its count running through all of them. Meanwhile each card in front lifts as a light
 * passes over it, opens to show what it gained (an example sentence with the word marked, its translation, and audio
 * whose bars grow in). Then the assistant's answer streams in, a word at a time. With
 * reduced motion the stage is simply shown as it ends.
 */
export class Assistant {
  private readonly ask: HTMLElement;
  private readonly tools: HTMLElement[];
  private readonly progress: HTMLElement;
  private readonly words: Element[];
  private readonly typed: HTMLElement;
  private readonly notes: Note[];

  constructor(
    private readonly root: HTMLElement,
    stage: Stage,
  ) {
    this.ask = one(root, "[data-chat-ask]");
    this.tools = all(root, "[data-chat-tool]");
    this.progress = one(root, "[data-chat-progress]");
    this.typed = one(root, "[data-chat-typed]");
    this.notes = all(root, "[data-note]").map((element) => {
      const more = one(element, "[data-note-more]");
      const bars = all(element, "[data-note-bar]");
      return {
        element,
        scan: one(element, "[data-note-scan]"),
        more,
        inner: [...one(more, ".note__inner").children],
        bars,
        heights: bars.map((bar) => Number(gsap.getProperty(bar, "scaleY"))),
      };
    });
    if (stage.reduced) {
      this.words = [];
      return;
    }
    this.words = new SplitText(one(root, "[data-chat-reply]"), { type: "words" }).words;
    // The cards open and close as the story goes: an open copy of them, unseen, keeps their room.
    const cards = one(root, ".enrich");
    const room = cards.cloneNode(true) as HTMLElement;
    room.classList.add("enrich--room");
    room.removeAttribute("data-depth");
    cards.after(room);
    this.clear();
    loopInView(root, () => this.story(), { start: "top 70%", end: "bottom 15%" });
  }

  /** Back to before the request: an empty field, no conversation, the cards as they were. */
  private clear(): void {
    gsap.set([this.ask, ...this.tools, ...this.words], { opacity: 0 });
    for (const tool of this.tools) tool.classList.add("is-working");
    this.progress.textContent = "0";
    this.typed.textContent = "Ask anything";
    this.typed.classList.remove("is-typing");
    this.root.classList.remove("is-thinking");
    for (const note of this.notes) {
      gsap.set(note.more, { height: 0 });
      gsap.set(note.scan, { xPercent: -100, opacity: 0 });
      gsap.set(note.bars, { scaleY: 0.08 });
    }
  }

  private story(): gsap.core.Timeline {
    // Tweens set their starting state when they start, not when the story is put together.
    const timeline = gsap.timeline({ defaults: { immediateRender: false } });
    timeline.call(() => this.clear(), undefined, 0);

    // The request is typed, a little unevenly, and sent.
    const typing = { letters: 0 };
    timeline.call(() => this.typed.classList.add("is-typing"), undefined, 0.5);
    timeline.to(
      typing,
      {
        letters: conversation.ask.length,
        duration: 1.7,
        ease: "power1.inOut",
        onUpdate: () => (this.typed.textContent = conversation.ask.slice(0, Math.round(typing.letters))),
      },
      0.5,
    );
    timeline.call(
      () => {
        this.typed.classList.remove("is-typing");
        this.typed.textContent = "Ask anything";
      },
      undefined,
      2.4,
    );
    timeline.fromTo(
      this.ask,
      { opacity: 0, y: 18, scale: 0.96, transformOrigin: "100% 100%" },
      { opacity: 1, y: 0, scale: 1, duration: 0.7, ease: "expo.out" },
      2.4,
    );

    // It thinks, then looks through the decks, and writes to every note, counting.
    timeline.call(() => this.root.classList.add("is-thinking"), undefined, 2.7);
    timeline.call(() => this.root.classList.remove("is-thinking"), undefined, 3.3);
    const [search, update] = this.tools;
    if (search != null) {
      timeline.fromTo(search, { opacity: 0, y: 8 }, { opacity: 1, y: 0, duration: 0.5, ease: "expo.out" }, 3.3);
      timeline.call(() => search.classList.remove("is-working"), undefined, 3.9);
    }
    if (update != null) {
      timeline.fromTo(update, { opacity: 0, y: 8 }, { opacity: 1, y: 0, duration: 0.5, ease: "expo.out" }, 4.0);
      const count = { n: 0 };
      timeline.to(
        count,
        {
          n: conversation.update.total,
          duration: 4.1,
          ease: "power1.inOut",
          onUpdate: () => (this.progress.textContent = String(Math.round(count.n))),
        },
        4.1,
      );
      timeline.call(() => update.classList.remove("is-working"), undefined, 8.25);
    }
    this.notes.forEach((note, index) => this.enrich(timeline, note, working[index] ?? 4));

    // The answer streams in, a word at a time.
    timeline.to(this.words, { opacity: 1, duration: 0.35, stagger: 0.045, ease: "power1.out" }, 8.5);

    // A while to take it in, then it all goes, for the next assistant.
    const out = 13;
    timeline.to(
      [this.ask, ...this.tools, ...this.words],
      { opacity: 0, y: -6, duration: 0.5, ease: "power2.in", stagger: 0.01 },
      out,
    );
    for (const note of this.notes) {
      timeline.to(note.more, { height: 0, duration: 0.6, ease: "power3.inOut" }, out + 0.1);
    }
    timeline.set([this.ask, ...this.tools, ...this.words], { y: 0 }, out + 0.8);
    return timeline;
  }

  /**
   * A card worked on: it lifts as the light passes over it, opens to show its example, its translation and its
   * audio, the recording's bars growing in, and settles.
   */
  private enrich(timeline: gsap.core.Timeline, note: Note, at: number): void {
    timeline.to(note.element, { y: -6, scale: 1.025, duration: 0.35, ease: "power2.out" }, at);
    timeline.fromTo(
      note.scan,
      { xPercent: -100, opacity: 1 },
      { xPercent: 100, duration: 1.1, ease: "power1.inOut" },
      at,
    );
    timeline.to(note.more, { height: "auto", duration: 0.8, ease: "expo.out" }, at + 0.3);
    timeline.fromTo(
      note.inner,
      { opacity: 0, y: 8, filter: "blur(6px)" },
      { opacity: 1, y: 0, filter: "blur(0px)", duration: 0.6, ease: "expo.out", stagger: 0.09 },
      at + 0.4,
    );
    timeline.to(
      note.bars,
      { scaleY: (index: number) => note.heights[index] ?? 1, duration: 0.5, ease: "back.out(3)", stagger: 0.025 },
      at + 0.7,
    );
    timeline.to(note.element, { y: 0, scale: 1, duration: 0.9, ease: "elastic.out(1, 0.55)" }, at + 0.75);
  }
}
