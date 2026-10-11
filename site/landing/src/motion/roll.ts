import { gsap } from "gsap";

/**
 * Rolls the text in `holder` (a .roll, whose children share one grid cell) to `text`: the old one rises away into a
 * blur as the new one comes up out of one, or the other way with `down` false. With `still`, it simply changes.
 */
export function rollTo(holder: HTMLElement, text: string, down: boolean, still: boolean): void {
  const current = holder.lastElementChild;
  if (current?.textContent === text) return;
  const next = document.createElement("span");
  next.textContent = text;
  holder.append(next);
  if (still) {
    current?.remove();
    return;
  }
  gsap.fromTo(
    next,
    { yPercent: down ? 70 : -70, opacity: 0, filter: "blur(4px)" },
    { yPercent: 0, opacity: 1, filter: "blur(0px)", duration: 0.6, ease: "expo.out", clearProps: "filter" },
  );
  if (current != null) {
    gsap.to(current, {
      yPercent: down ? -70 : 70,
      opacity: 0,
      filter: "blur(4px)",
      duration: 0.35,
      ease: "power2.in",
      onComplete: () => current.remove(),
    });
  }
}
