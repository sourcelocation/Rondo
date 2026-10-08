import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { all, hasFinePointer, one } from "./dom";

type Kind = "phone" | "tablet" | "laptop";

/**
 * The turns, one device at a time: the platform it turns into. After eight turns every device is back where it
 * started (the phone on Android, the tablet on iPadOS, the laptop on Windows), so the story loops without a seam.
 */
const turns: readonly (readonly [Kind, string])[] = [
  ["laptop", "linux"],
  ["phone", "ios"],
  ["laptop", "web"],
  ["tablet", "android"],
  ["laptop", "macos"],
  ["phone", "android"],
  ["laptop", "windows"],
  ["tablet", "ipados"],
];

/** A turn's length, in seconds. */
const TURN = 3.4;

/** How far each device drifts with the pointer, in pixels: the nearer, the more. */
const depth: Record<Kind, number> = { laptop: 6, tablet: 14, phone: 18 };

/** One device: its screen, its study session, and what presses its buttons (a finger, or a pointer). */
class Device {
  readonly kind: Kind;
  private readonly screen: HTMLElement;
  private readonly study: HTMLElement;
  private readonly cards: HTMLElement[];
  private readonly show: HTMLElement;
  private readonly good: HTMLElement;
  private readonly touch: HTMLElement | null;
  private readonly cursor: SVGElement | null;
  private pointing: gsap.core.Timeline | null = null;
  private index = 0;

  constructor(readonly element: HTMLElement) {
    this.kind = (element.dataset.dev ?? "phone") as Kind;
    this.screen = one(element, ".laptop__screen, .tablet__screen, .phone__screen");
    this.study = one(element, "[data-study]");
    this.cards = all(this.study, "[data-card]");
    this.show = one(this.study, "[data-show]");
    this.good = one(this.study, '[data-rate="Good"]');
    this.touch = element.querySelector<HTMLElement>("[data-touch]");
    this.cursor = element.querySelector<SVGElement>("[data-cursor]");
  }

  /** Turns into another platform: the styles redraw its frame, and its mark beneath it changes. */
  platform(os: string): void {
    this.element.dataset.os = os;
  }

  /** Asks for the answer, which is there the moment the button is pressed. */
  ask(): void {
    this.press(this.show, () => (this.study.dataset.revealed = ""));
  }

  /** Grades the card Good, and the next one comes off the deck. */
  grade(): void {
    this.press(this.good, () => this.next());
  }

  private press(target: HTMLElement, then: () => void): void {
    if (this.cursor != null) {
      this.point(this.cursor, target, then);
      return;
    }
    const at = this.centre(target);
    if (this.touch != null) {
      gsap
        .timeline()
        .set(this.touch, { x: at.x, y: at.y, scale: 0.4, opacity: 0 })
        .to(this.touch, { scale: 1, opacity: 1, duration: 0.12, ease: "power2.out" })
        .to(this.touch, { scale: 1.7, opacity: 0, duration: 0.5, ease: "power2.out" });
    }
    this.squeeze(target);
    gsap.delayedCall(0.08, then);
  }

  /**
   * The laptop's pointer glides to the button, clicks it with its tip, and rests a little aside. Distances are in
   * the pointer's own width, so they keep to the picture at any size.
   */
  private point(cursor: SVGElement, target: HTMLElement, then: () => void): void {
    // The last press may still be resting aside; left running, it would pull the pointer off this button.
    this.pointing?.kill();
    const size = parseFloat(getComputedStyle(cursor).width);
    // The arrow's tip, from its box's corner: the path starts at 1.5 of the 16-wide view box.
    const tip = (size * 1.5) / 16;
    const shown = Number(gsap.getProperty(cursor, "opacity")) > 0.5;
    const timeline = gsap.timeline();
    this.pointing = timeline;
    if (!shown) {
      const at = this.centre(target);
      timeline
        .set(cursor, { x: at.x + 4 * size, y: at.y + 3 * size, opacity: 0 })
        .to(cursor, { opacity: 1, duration: 0.3 });
    }
    // The window may still be settling into a new frame, so the glide aims at where the button is on every frame.
    const glide = { progress: 0, x: 0, y: 0 };
    timeline
      .to(
        glide,
        {
          progress: 1,
          duration: 0.55,
          ease: "power3.inOut",
          onStart: () => {
            glide.x = Number(gsap.getProperty(cursor, "x"));
            glide.y = Number(gsap.getProperty(cursor, "y"));
          },
          onUpdate: () => {
            const at = this.centre(target);
            gsap.set(cursor, {
              x: gsap.utils.interpolate(glide.x, at.x - tip, glide.progress),
              y: gsap.utils.interpolate(glide.y, at.y - tip, glide.progress),
            });
          },
        },
        shown ? 0 : 0.05,
      )
      .to(cursor, { scale: 0.82, duration: 0.07, ease: "power2.out", transformOrigin: "0% 0%" })
      .call(() => {
        this.squeeze(target);
        then();
      })
      .to(cursor, { scale: 1, duration: 0.2, ease: "power2.out" })
      .to(
        cursor,
        {
          x: () => this.centre(target).x + 2 * size,
          y: () => this.centre(target).y + 1.6 * size,
          duration: 0.8,
          ease: "power2.out",
        },
        "+=0.15",
      );
  }

  private squeeze(target: HTMLElement): void {
    gsap.fromTo(target, { scale: 0.93 }, { scale: 1, duration: 0.55, ease: "elastic.out(1, 0.5)" });
  }

  /** The graded card leaves to the left as the next one arrives from the right, question side up. */
  private next(): void {
    const current = this.cards[this.index];
    this.index = (this.index + 1) % this.cards.length;
    const next = this.cards[this.index];
    if (current == null || next == null) return;
    gsap
      .timeline()
      .to(current, { xPercent: -12, opacity: 0, filter: "blur(4px)", duration: 0.28, ease: "power2.in" })
      .call(() => {
        delete this.study.dataset.revealed;
        current.removeAttribute("data-current");
        next.setAttribute("data-current", "");
      })
      .fromTo(
        next,
        { xPercent: 12, opacity: 0, filter: "blur(4px)" },
        { xPercent: 0, opacity: 1, filter: "blur(0px)", duration: 0.5, ease: "expo.out", clearProps: "filter" },
      );
  }

  /** The middle of `target`, from the screen's corner, in the screen's own pixels: it's scaled as it rises in. */
  private centre(target: HTMLElement): { x: number; y: number } {
    const screen = this.screen.getBoundingClientRect();
    const scale = screen.width / this.screen.offsetWidth || 1;
    const box = target.getBoundingClientRect();
    return {
      x: (box.left - screen.left + box.width / 2) / scale,
      y: (box.top - screen.top + box.height / 2) / scale,
    };
  }
}

/**
 * The phone, the tablet and the laptop of the native-apps section, told on a loop while it's on screen: a device
 * turns into another platform, then studies a card, its answer there the moment it's asked for. The devices float
 * a little, and lean apart with the pointer, the nearest the most.
 */
export function playPlatforms(root: HTMLElement, still: boolean): void {
  if (still) return;
  const devices = new Map(all(root, "[data-dev]").map((element) => [element.dataset.dev, new Device(element)]));

  const story = gsap.timeline({ paused: true, repeat: -1 });
  turns.forEach(([kind, os], index) => {
    const device = devices.get(kind);
    if (device == null) return;
    const at = index * TURN;
    story.call(() => device.platform(os), undefined, at);
    // The pointer needs a moment to reach its button; a finger is just there.
    const lead = kind === "laptop" ? 0.55 : 0;
    story.call(() => device.ask(), undefined, at + 1.2 - lead);
    story.call(() => device.grade(), undefined, at + 2.3 - lead);
  });
  story.set({}, {}, turns.length * TURN);

  ScrollTrigger.create({
    trigger: root,
    start: "top 75%",
    end: "bottom 20%",
    onToggle: ({ isActive }) => {
      if (isActive) story.play();
      else story.pause();
    },
  });

  for (const [index, float] of all(root, "[data-float]").entries()) {
    gsap.to(float, {
      yPercent: index === 0 ? -0.8 : -1.6,
      duration: 3.4 + index * 0.6,
      ease: "sine.inOut",
      yoyo: true,
      repeat: -1,
      delay: index * 0.4,
    });
  }

  if (!hasFinePointer()) return;
  const drift = [...devices.values()].map((device) => ({
    depth: depth[device.kind],
    x: gsap.quickTo(device.element, "x", { duration: 1.4, ease: "power3.out" }),
    y: gsap.quickTo(device.element, "y", { duration: 1.4, ease: "power3.out" }),
  }));
  window.addEventListener(
    "pointermove",
    (event) => {
      const x = event.clientX / window.innerWidth - 0.5;
      const y = event.clientY / window.innerHeight - 0.5;
      for (const device of drift) {
        device.x(x * device.depth);
        device.y(y * device.depth * 0.6);
      }
    },
    { passive: true },
  );
}
