import { gsap } from "gsap";
import { all, one } from "./dom";

/**
 * The questions, opening and closing smoothly: opening one, its answer grows open as its paragraphs come in out of
 * a blur, and the one that was open closes. The motion keeps one open at a time itself (rather than <details>'s
 * name), so the one closing can close smoothly too. With reduced motion they open and close at once, as <details> do.
 */
export class Questions {
  private readonly items: HTMLDetailsElement[];

  constructor(list: HTMLElement, reduced: boolean) {
    this.items = all(list, "details", HTMLDetailsElement);
    if (reduced) return;
    for (const item of this.items) {
      item.removeAttribute("name");
      one(item, "summary").addEventListener("click", (event) => {
        event.preventDefault();
        if (item.open && !item.classList.contains("is-closing")) {
          this.close(item);
          return;
        }
        for (const other of this.items) if (other !== item && other.open) this.close(other);
        this.open(item);
      });
    }
  }

  private open(item: HTMLDetailsElement): void {
    const answer = one(item, "[data-faq-answer]");
    const from = item.open ? answer.offsetHeight : 0;
    item.classList.remove("is-closing");
    item.open = true;
    gsap.killTweensOf(answer);
    gsap.fromTo(answer, { height: from }, { height: "auto", duration: 0.8, ease: "expo.out", clearProps: "height" });
    gsap.fromTo(
      one(answer, ".faq__body").children,
      { opacity: 0, y: 10, filter: "blur(6px)" },
      { opacity: 1, y: 0, filter: "blur(0px)", duration: 0.7, ease: "expo.out", stagger: 0.07, clearProps: "all" },
    );
  }

  private close(item: HTMLDetailsElement): void {
    const answer = one(item, "[data-faq-answer]");
    item.classList.add("is-closing");
    gsap.killTweensOf(answer);
    gsap.fromTo(
      answer,
      { height: answer.offsetHeight },
      {
        height: 0,
        duration: 0.55,
        ease: "power3.inOut",
        onComplete: () => {
          item.open = false;
          item.classList.remove("is-closing");
          gsap.set(answer, { clearProps: "height" });
        },
      },
    );
  }
}
