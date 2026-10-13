import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { SplitText } from "gsap/SplitText";
import { all } from "./dom";

/** How an element marked data-reveal comes into view. */
type Kind = "fade" | "rise" | "words" | "lines" | "hero";

const kinds: readonly string[] = ["fade", "rise", "words", "lines", "hero"];

function kindOf(element: HTMLElement): Kind {
  const kind = element.dataset.reveal ?? "fade";
  return (kinds.includes(kind) ? kind : "fade") as Kind;
}

/**
 * Brings what is marked data-reveal into view out of a soft blur, rising a little: text word by word or line by
 * line, anything else whole. Elements marked data-reveal-manual wait for `play` (the page's opening); the rest play
 * as they scroll into view, once the page has arrived (`watch`). Until then base.css keeps them hidden; with reduced
 * motion they're simply shown, as they are.
 */
export class Reveals {
  private readonly waiting = new Map<HTMLElement, () => gsap.core.Timeline>();
  private readonly scrolled: [HTMLElement, () => gsap.core.Timeline][] = [];

  constructor(
    private readonly root: ParentNode,
    private readonly reduced: boolean,
  ) {}

  /** Puts every element in its starting state (splitting text), out of view until it's revealed. */
  prepare(): void {
    if (this.reduced) return;
    for (const element of all(this.root, "[data-reveal]")) {
      const reveal = this.prepareOne(element, kindOf(element));
      if (element.hasAttribute("data-reveal-manual")) this.waiting.set(element, reveal);
      else this.scrolled.push([element, reveal]);
    }
  }

  /** Reveals each element that isn't waiting for `play` as it scrolls into view (at once, if it's in view). */
  watch(): void {
    for (const [element, reveal] of this.scrolled.splice(0)) {
      ScrollTrigger.create({ trigger: element, start: "top 90%", once: true, onEnter: () => reveal() });
    }
  }

  /** Reveals an element that waits for it; null when it was shown already or isn't on the page. */
  play(element: Element | null): gsap.core.Timeline | null {
    if (!(element instanceof HTMLElement)) return null;
    const reveal = this.waiting.get(element);
    if (reveal == null) return null;
    this.waiting.delete(element);
    return reveal();
  }

  /** Reveals everything still waiting, at once (when the page has no opening to do it). */
  playAll(): void {
    for (const element of [...this.waiting.keys()]) this.play(element);
  }

  /** Sets an element's starting state and returns what reveals it. */
  private prepareOne(element: HTMLElement, kind: Kind): () => gsap.core.Timeline {
    switch (kind) {
      case "words":
        return this.split(element, "words", { yPercent: 45, blur: 14, stagger: 0.06, duration: 1.5 });
      case "lines":
        return this.split(element, "lines", { yPercent: 30, blur: 10, stagger: 0.1, duration: 1.4 });
      case "hero":
        return this.hero(element);
      case "rise":
        return this.whole(element, { y: 80, scale: 0.97, blur: 18, duration: 1.8 });
      case "fade":
        return this.whole(element, { y: 22, scale: 1, blur: 10, duration: 1.3 });
    }
  }

  private whole(
    element: HTMLElement,
    from: { y: number; scale: number; blur: number; duration: number },
  ): () => gsap.core.Timeline {
    gsap.set(element, { opacity: 0, y: from.y, scale: from.scale, filter: `blur(${from.blur}px)` });
    return () =>
      gsap.timeline().to(element, {
        opacity: 1,
        y: 0,
        scale: 1,
        filter: "blur(0px)",
        duration: from.duration,
        ease: "expo.out",
        // A filter or transform left behind would give the element a stacking context of its own.
        clearProps: "filter,transform",
      });
  }

  private split(
    element: HTMLElement,
    type: "words" | "lines",
    from: { yPercent: number; blur: number; stagger: number; duration: number },
  ): () => gsap.core.Timeline {
    const split = new SplitText(element, { type, aria: "auto" });
    const pieces = type === "words" ? split.words : split.lines;
    gsap.set(element, { opacity: 1 });
    gsap.set(pieces, { opacity: 0, yPercent: from.yPercent, filter: `blur(${from.blur}px)` });
    return () =>
      gsap.timeline({ onComplete: () => split.revert() }).to(pieces, {
        opacity: 1,
        yPercent: 0,
        filter: "blur(0px)",
        duration: from.duration,
        stagger: from.stagger,
        ease: "expo.out",
      });
  }

  /**
   * A headline: each word condenses out of a wide, blurred haze into its tight spacing, like a breath out. Its letters
   * gather in from around the middle on transforms alone, each a layer the GPU moves: tightening the spacing itself
   * would lay the line out again every frame, and the letters would step into place a pixel at a time. Each comes to
   * rest where the whole word's kerning puts it, so nothing shifts when the words are joined back up.
   */
  private hero(element: HTMLElement): () => gsap.core.Timeline {
    const kerned = this.letterStarts(element);
    const split = new SplitText(element, { type: "words,chars", aria: "auto" });
    const box = element.getBoundingClientRect();
    const zoom = box.width / element.offsetWidth || 1;
    const middle = box.left + box.width / 2;
    const letters = split.chars.map((char, index) => {
      const { left, width } = char.getBoundingClientRect();
      const rest = kerned.length === split.chars.length ? (kerned[index]! - left) / zoom : 0;
      return {
        rest,
        wide: rest + ((left + width / 2 - middle) / zoom) * 0.12,
        word: split.words.findIndex((word) => word.contains(char)),
      };
    });
    gsap.set(element, { opacity: 1 });
    gsap.set(split.words, { opacity: 0, yPercent: 16, filter: "blur(28px)" });
    gsap.set(split.chars, { x: (index: number) => letters[index]!.wide, force3D: true });
    const motion = { duration: 2.2, ease: "expo.out" };
    return () =>
      gsap
        .timeline({ onComplete: () => split.revert() })
        .to(split.words, { opacity: 1, yPercent: 0, filter: "blur(0px)", stagger: 0.16, ...motion }, 0)
        .to(
          split.chars,
          {
            x: (index: number) => letters[index]!.rest,
            stagger: (index: number) => letters[index]!.word * 0.16,
            force3D: true,
            ...motion,
          },
          0,
        );
  }

  /** Where each letter of an element's text starts on screen, laid out whole, with its kerning. */
  private letterStarts(element: HTMLElement): number[] {
    const starts: number[] = [];
    const range = document.createRange();
    const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node != null; node = walker.nextNode()) {
      let offset = 0;
      for (const letter of node.textContent ?? "") {
        if (letter.trim() !== "") {
          range.setStart(node, offset);
          range.setEnd(node, offset + letter.length);
          starts.push(range.getBoundingClientRect().left);
        }
        offset += letter.length;
      }
    }
    return starts;
  }
}
