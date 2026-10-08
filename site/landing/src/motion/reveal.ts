import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { SplitText } from "gsap/SplitText";
import { all } from "./dom";

/** How an element marked data-reveal comes into view. */
type Kind = "fade" | "rise" | "words" | "lines" | "hero" | "logo";

const kinds: readonly string[] = ["fade", "rise", "words", "lines", "hero", "logo"];

function kindOf(element: HTMLElement): Kind {
  const kind = element.dataset.reveal ?? "fade";
  return (kinds.includes(kind) ? kind : "fade") as Kind;
}

/**
 * Brings what is marked data-reveal into view out of a soft blur, rising a little: text word by word or line by
 * line, anything else whole. Elements marked data-reveal-manual wait for `play` (the intro's); the rest play as
 * they scroll into view. With reduced motion everything is simply shown.
 */
export class Reveals {
  private readonly waiting = new Map<HTMLElement, () => gsap.core.Timeline>();

  constructor(
    private readonly root: ParentNode,
    private readonly reduced: boolean,
  ) {}

  /** Prepares every element (splitting text) and sets the scroll-driven ones to play on entering. */
  start(): void {
    for (const element of all(this.root, "[data-reveal]")) {
      // The motion takes over from the CSS fallback that would show the element on its own.
      element.style.animation = "none";
      if (this.reduced) {
        gsap.set(element, { opacity: 1 });
        continue;
      }
      const reveal = this.prepare(element, kindOf(element));
      if (element.hasAttribute("data-reveal-manual")) {
        this.waiting.set(element, reveal);
      } else {
        ScrollTrigger.create({ trigger: element, start: "top 90%", once: true, onEnter: () => reveal() });
      }
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

  /** Reveals everything still waiting, at once (when there is no intro to do it). */
  playAll(): void {
    for (const element of [...this.waiting.keys()]) this.play(element);
  }

  /** Sets an element's starting state and returns what reveals it. */
  private prepare(element: HTMLElement, kind: Kind): () => gsap.core.Timeline {
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
      case "logo":
        return this.logo(element);
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

  /** The logo: out of a blur in place, its transform left alone (the intro's flight lands on it). */
  private logo(element: HTMLElement): () => gsap.core.Timeline {
    gsap.set(element, { opacity: 0, filter: "blur(10px)" });
    return () =>
      gsap
        .timeline()
        .to(element, { opacity: 1, filter: "blur(0px)", duration: 1.3, ease: "expo.out", clearProps: "filter" });
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
   * The headline: each word condenses out of a wide, blurred haze into its tight spacing, like a breath out.
   * Words keep their kerning (split by letters, they would lose it).
   */
  private hero(element: HTMLElement): () => gsap.core.Timeline {
    const split = new SplitText(element, { type: "words", aria: "auto" });
    const spacing = getComputedStyle(element).letterSpacing;
    const size = parseFloat(getComputedStyle(element).fontSize);
    gsap.set(element, { opacity: 1 });
    gsap.set(split.words, {
      opacity: 0,
      yPercent: 16,
      filter: "blur(28px)",
      letterSpacing: `${parseFloat(spacing) + size * 0.06}px`,
    });
    return () =>
      gsap.timeline({ onComplete: () => split.revert() }).to(split.words, {
        opacity: 1,
        yPercent: 0,
        filter: "blur(0px)",
        letterSpacing: spacing,
        duration: 2.2,
        stagger: 0.16,
        ease: "expo.out",
      });
  }
}
