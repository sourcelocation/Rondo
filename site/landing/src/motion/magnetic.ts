import { gsap } from "gsap";

/** Makes buttons lean toward the pointer while it's over them, and settle back when it leaves. */
export function magnetize(elements: readonly HTMLElement[]): void {
  for (const element of elements) {
    const x = gsap.quickTo(element, "x", { duration: 0.7, ease: "power3.out" });
    const y = gsap.quickTo(element, "y", { duration: 0.7, ease: "power3.out" });
    element.addEventListener("pointermove", (event) => {
      if (event.pointerType !== "mouse") return;
      const box = element.getBoundingClientRect();
      x((event.clientX - (box.left + box.width / 2)) * 0.28);
      y((event.clientY - (box.top + box.height / 2)) * 0.36);
    });
    element.addEventListener("pointerleave", () => {
      x(0);
      y(0);
    });
  }
}
