import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { one } from "./dom";

/** How far the portrait leans toward the pointer, in degrees. */
const TILT = 7;

/**
 * The maker's portrait: scrolling in, it opens out of a smaller window while the picture settles from close up.
 * It then leans toward a mouse that is over it, a light sliding across it. With reduced motion it's simply there.
 */
export class Portrait {
  constructor(root: HTMLElement, still: boolean, fine: boolean) {
    if (still) return;
    const card = one(root, "[data-portrait-card]");
    const frame = one(root, "[data-portrait-frame]");
    const image = one(root, "[data-portrait-image]");
    this.unveil(root, frame, image);
    if (fine) this.lean(root, card);
  }

  private unveil(root: HTMLElement, frame: HTMLElement, image: HTMLElement): void {
    gsap.set(frame, { clipPath: "inset(16% 12% 16% 12% round 44px)" });
    gsap.set(image, { scale: 1.3 });
    ScrollTrigger.create({
      trigger: root,
      start: "top 85%",
      once: true,
      onEnter: () => {
        gsap
          .timeline()
          .to(frame, {
            clipPath: "inset(0% 0% 0% 0% round 28px)",
            duration: 1.8,
            ease: "expo.out",
            clearProps: "clipPath",
          })
          .to(image, { scale: 1, duration: 2.4, ease: "expo.out" }, 0);
      },
    });
  }

  private lean(root: HTMLElement, card: HTMLElement): void {
    gsap.set(card, { transformPerspective: 900 });
    const turn = { duration: 0.9, ease: "power3.out" };
    const rotateX = gsap.quickTo(card, "rotationX", turn);
    const rotateY = gsap.quickTo(card, "rotationY", turn);
    const move = (x: number, y: number) => {
      rotateX(-y * TILT);
      rotateY(x * TILT);
      card.style.setProperty("--sheen-x", `${(50 + x * 80).toFixed(1)}%`);
      card.style.setProperty("--sheen-y", `${(50 + y * 80).toFixed(1)}%`);
    };
    const rest = () => {
      move(0, 0);
      card.style.removeProperty("--sheen-x");
      card.style.removeProperty("--sheen-y");
    };
    root.addEventListener("pointermove", (event) => {
      if (event.pointerType !== "mouse") return;
      const box = card.getBoundingClientRect();
      move((event.clientX - box.left) / box.width - 0.5, (event.clientY - box.top) / box.height - 0.5);
    });
    root.addEventListener("pointerleave", rest);
  }
}
