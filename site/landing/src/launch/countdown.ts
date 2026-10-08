import { gsap } from "gsap";
import { all, prefersReducedMotion } from "../motion/dom";
import { LAUNCH } from "./launch";

const SECOND = 1000;
const units = [
  { name: "days", size: 86_400 },
  { name: "hours", size: 3_600 },
  { name: "minutes", size: 60 },
  { name: "seconds", size: 1 },
] as const;

/** The time left until the launch, in whole days, hours, minutes and seconds. */
function remaining(now: number): Record<(typeof units)[number]["name"], number> {
  let left = Math.max(0, Math.floor((LAUNCH - now) / SECOND));
  const parts = { days: 0, hours: 0, minutes: 0, seconds: 0 };
  for (const unit of units) {
    parts[unit.name] = Math.floor(left / unit.size);
    left %= unit.size;
  }
  return parts;
}

/**
 * The time left until the launch, ticking each second. Each digit that changes rolls: the old one rises away into a
 * blur and the new one rises into place, so the seconds keep a quiet pulse.
 */
export class Countdown {
  private readonly digits = new Map<string, HTMLElement[]>();
  private readonly still = prefersReducedMotion();

  constructor(root: HTMLElement) {
    for (const value of all(root, "[data-unit]")) this.digits.set(value.dataset.unit ?? "", all(value, ".digit"));
    this.update(true);
    const tick = () => {
      this.update(false);
      setTimeout(tick, SECOND - (Date.now() % SECOND) + 5);
    };
    setTimeout(tick, SECOND - (Date.now() % SECOND) + 5);
  }

  private update(first: boolean): void {
    const parts = remaining(Date.now());
    units.forEach((unit, index) => {
      const text = String(parts[unit.name]).padStart(2, "0").slice(-2);
      this.digits.get(unit.name)?.forEach((digit, place) => {
        this.roll(digit, text[place] ?? "0", first ? 0.3 + index * 0.12 + place * 0.05 : 0);
      });
    });
  }

  private roll(digit: HTMLElement, char: string, delay: number): void {
    const current = digit.lastElementChild;
    if (current instanceof HTMLElement && current.textContent === char) return;
    const next = document.createElement("span");
    next.textContent = char;
    digit.append(next);
    if (this.still) {
      current?.remove();
      return;
    }
    gsap.fromTo(
      next,
      { yPercent: 60, opacity: 0, filter: "blur(6px)" },
      { yPercent: 0, opacity: 1, filter: "blur(0px)", duration: 0.7, delay, ease: "expo.out", clearProps: "filter" },
    );
    if (current != null) {
      gsap.to(current, {
        yPercent: -60,
        opacity: 0,
        filter: "blur(6px)",
        duration: 0.5,
        delay,
        ease: "power2.in",
        onComplete: () => current.remove(),
      });
    }
  }
}
