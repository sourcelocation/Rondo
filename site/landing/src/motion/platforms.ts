import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import type { Gadget } from "../content/devices";
import { all, one } from "./dom";
import type { Stage } from "./stage";

/** How far each device drifts with the pointer, in pixels: the nearer, the more. */
const depth: Record<Gadget, number> = { laptop: 6, tablet: 14, phone: 18 };

/**
 * How each device moves as the section scrolls past, from where it is as it comes in to where it is as it leaves:
 * the nearer, the farther it travels, and a little turn. The laptop's lid also comes up the last few degrees.
 */
const passing: Record<Gadget, readonly [gsap.TweenVars, gsap.TweenVars]> = {
  laptop: [{ yPercent: 7 }, { yPercent: -5 }],
  tablet: [
    { yPercent: 16, rotation: -2.5 },
    { yPercent: -12, rotation: 1 },
  ],
  phone: [
    { yPercent: 26, rotation: 3 },
    { yPercent: -20, rotation: -1.5 },
  ],
};

/** The order the devices take turns studying in. */
const turns: readonly Gadget[] = ["laptop", "phone", "tablet"];

/** One device: its screen, its study session, and what presses its buttons (a finger, or a pointer). */
class Device {
  readonly kind: Gadget;
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
    this.kind = (element.dataset.dev ?? "phone") as Gadget;
    this.screen = one(element, "[data-screen]");
    this.study = one(element, "[data-study]");
    this.cards = all(this.study, "[data-card]");
    this.show = one(this.study, "[data-show]");
    this.good = one(this.study, '[data-rate="Good"]');
    this.touch = element.querySelector<HTMLElement>("[data-touch]");
    this.cursor = element.querySelector<SVGElement>("[data-cursor]");
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

  /** The middle of `target`, from the screen's corner, in the screen's own pixels: it may be scaled as it moves. */
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
 * The native-apps section: as it scrolls past, the devices move a little at their own depths, the laptop's lid
 * coming up the last few degrees; they float, and lean apart with the pointer, the nearest the most. While it's on
 * screen they take turns studying a card.
 */
export class Platforms {
  private readonly devices: Device[];

  constructor(stage: HTMLElement, { reduced, fine, pointer }: Stage) {
    const trio = one(stage, "[data-trio]");
    this.devices = all(trio, "[data-dev]").map((element) => new Device(element));
    if (reduced) return;

    this.pass(stage);
    this.studies(stage);
    for (const [index, float] of all(trio, "[data-float]").entries()) {
      gsap.to(float, {
        yPercent: index === 0 ? -0.8 : -1.6,
        duration: 3.4 + index * 0.6,
        ease: "sine.inOut",
        yoyo: true,
        repeat: -1,
        delay: index * 0.4,
      });
    }
    if (!fine) return;
    const drift = this.devices.map((device) => ({
      depth: depth[device.kind],
      x: gsap.quickTo(device.element, "x", { duration: 1.4, ease: "power3.out" }),
      y: gsap.quickTo(device.element, "y", { duration: 1.4, ease: "power3.out" }),
    }));
    window.addEventListener(
      "pointermove",
      () => {
        for (const device of drift) {
          device.x(pointer.x * device.depth);
          device.y(pointer.y * device.depth * 0.6);
        }
      },
      { passive: true },
    );
  }

  /** The devices move with the scroll, all the way through the section's passing. */
  private pass(stage: HTMLElement): void {
    for (const device of this.devices) {
      const [from, to] = passing[device.kind];
      gsap.fromTo(device.element, from, {
        ...to,
        ease: "none",
        scrollTrigger: { trigger: stage, start: "top bottom", end: "bottom top", scrub: 0.8 },
      });
    }
    const lid = stage.querySelector<HTMLElement>("[data-lid]");
    if (lid == null) return;
    gsap.fromTo(
      lid,
      { rotationX: -24 },
      {
        rotationX: 0,
        ease: "power1.out",
        scrollTrigger: { trigger: stage, start: "top bottom", end: "center center", scrub: 0.8 },
      },
    );
  }

  /** While the devices are on screen, one of them studies a card every few seconds, each in turn. */
  private studies(stage: HTMLElement): void {
    let turn = 0;
    const studying = (): Device | undefined => this.devices.find((device) => device.kind === turns[turn]);
    const loop = gsap
      .timeline({ paused: true, repeat: -1 })
      .call(() => studying()?.ask(), undefined, 0.9)
      .call(() => studying()?.grade(), undefined, 2.0)
      .call(() => (turn = (turn + 1) % turns.length), undefined, 2.6)
      .set({}, {}, 3.2);
    ScrollTrigger.create({
      trigger: stage,
      start: "top 75%",
      end: "bottom 25%",
      onToggle: ({ isActive }) => {
        if (isActive) loop.play();
        else loop.pause();
      },
    });
  }
}
