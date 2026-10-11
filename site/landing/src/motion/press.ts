import { gsap } from "gsap";
import { all, one } from "./dom";
import { whileOnScreen } from "./loop";
import { clamp, damp } from "./math";
import { rollTo } from "./roll";
import type { Stage } from "./stage";

/**
 * The logo, built: as it comes into view, its guides draw out from the logo's corner and across the panel, its room
 * fills in, the mark sweeps round into view the way its arrow turns and the word slides in beside it; then the
 * measurements are named. Afterwards it leans a little toward a mouse.
 */
export function build(root: HTMLElement, stage: Stage): void {
  if (stage.reduced) return;
  const lines = all(root, "[data-build-line]");
  const boxes = all(root, "[data-build-box]");
  const notes = all(root, "[data-build-note]");
  const logo = one(root, "[data-build-logo]");
  const mark = one(logo, "[data-logo-mark]", SVGElement);
  const word = one(logo, "[data-logo-word]", SVGElement);
  gsap.set(lines, { scaleX: 0, transformOrigin: "50% 50%" });
  gsap.set(lines.at(-1) ?? [], { scaleX: 1, scaleY: 0 });
  gsap.set(boxes, { opacity: 0, scale: 0.9 });
  gsap.set(notes, { opacity: 0, y: 6 });
  gsap.set(mark, { opacity: 0, rotate: -90, scale: 0.6, transformOrigin: "50% 50%" });
  gsap.set(word, { opacity: 0, x: 40, filter: "blur(10px)" });

  const timeline = gsap.timeline({
    paused: true,
    defaults: { ease: "expo.out" },
  });
  timeline
    .to(lines.slice(0, 2), { scaleX: 1, duration: 1.6, stagger: 0.12, ease: "expo.inOut" }, 0)
    .to(lines.at(-1) ?? [], { scaleY: 1, duration: 1.4, ease: "expo.inOut" }, 0.2)
    .to(boxes, { opacity: 1, scale: 1, duration: 1, stagger: 0.12 }, 0.5)
    .to(mark, { opacity: 1, rotate: 0, scale: 1, duration: 1.4, ease: "back.out(1.6)" }, 0.75)
    .to(word, { opacity: 1, x: 0, filter: "blur(0px)", duration: 1.3, clearProps: "filter" }, 1)
    .to(notes, { opacity: 1, y: 0, duration: 0.8, stagger: 0.1 }, 1.5);

  const observer = new IntersectionObserver(
    ([entry]) => {
      if (entry?.isIntersecting !== true) return;
      timeline.play();
      observer.disconnect();
    },
    { threshold: 0.35 },
  );
  observer.observe(root);

  if (!stage.fine) return;
  const lean = { x: 0, y: 0 };
  gsap.set(logo, { transformPerspective: 900 });
  whileOnScreen(root, (_time, deltaMs) => {
    const seconds = Math.min(deltaMs / 1000, 1 / 20);
    const box = root.getBoundingClientRect();
    const x = clamp((stage.pointer.clientX - (box.left + box.width / 2)) / box.width, -0.5, 0.5);
    const y = clamp((stage.pointer.clientY - (box.top + box.height / 2)) / box.height, -0.5, 0.5);
    lean.x = damp(lean.x, x, 4, seconds);
    lean.y = damp(lean.y, y, 4, seconds);
    gsap.set(logo, { rotationY: lean.x * 14, rotationX: lean.y * -10 });
  });
}

/** Shows a copy button's label as "Copied", its icon a tick, for a moment. */
function copied(button: HTMLElement, label: HTMLElement, back: string): void {
  button.classList.add("is-copied");
  rollTo(label, "Copied", true, false);
  gsap.delayedCall(1.6, () => {
    button.classList.remove("is-copied");
    rollTo(label, back, false, false);
  });
}

/** A button that copies the text of what `data-copy` names, as it reads on the page. */
export function copy(button: HTMLElement): void {
  const target = one(document, button.dataset.copy ?? "");
  const label = one(button, "[data-copy-label]");
  button.addEventListener("click", () => {
    void navigator.clipboard.writeText(target.innerText.trim()).then(() => copied(button, label, "Copy"));
  });
}

/** A colour that copies its value, which rolls to "Copied" and back. */
export function swatch(button: HTMLElement): void {
  const hex = button.dataset.swatch ?? "";
  const label = one(button, "[data-copy-label]");
  button.addEventListener("click", () => {
    void navigator.clipboard.writeText(hex).then(() => copied(button, label, hex));
  });
}

/** Geist, at the weight the pointer chooses across its card, from Thin to Black, easing back when it leaves. */
export function weight(card: HTMLElement, stage: Stage): void {
  const value = one(card, "[data-weight-value]");
  const state = { weight: 400 };
  const show = () => {
    card.style.setProperty("--weight", state.weight.toFixed(0));
    value.textContent = String(Math.round(state.weight / 10) * 10);
  };
  card.addEventListener("pointermove", (event) => {
    const box = card.getBoundingClientRect();
    const target = 100 + clamp((event.clientX - box.left) / box.width, 0, 1) * 800;
    gsap.to(state, {
      weight: target,
      duration: stage.reduced ? 0 : 0.6,
      ease: "power3.out",
      overwrite: true,
      onUpdate: show,
    });
  });
  card.addEventListener("pointerleave", () => {
    gsap.to(state, {
      weight: 400,
      duration: stage.reduced ? 0 : 1.2,
      ease: "elastic.out(1, 0.5)",
      overwrite: true,
      onUpdate: show,
    });
  });
}
