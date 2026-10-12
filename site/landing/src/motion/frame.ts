import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { depth } from "./depth";
import { all } from "./dom";
import { magnetize } from "./magnetic";
import type { Light } from "./scenery/light";
import type { Stage } from "./stage";

/** A mood of the light: how strongly it shows, and how much of each of its four colours. */
export interface Mood {
  readonly strength: number;
  readonly weights: readonly [number, number, number, number];
}

/** The page's moods, by the sections' data-mood. */
const moods = {
  hero: { strength: 1, weights: [1, 0.9, 0.85, 0.7] },
  collection: { strength: 0.62, weights: [0.9, 0.75, 0.6, 0.55] },
  source: { strength: 0.36, weights: [0.75, 0.45, 0.25, 1] },
  sync: { strength: 0.16, weights: [0.8, 0.55, 0.3, 0.7] },
  apps: { strength: 0.48, weights: [0.85, 1, 0.55, 0.45] },
  anki: { strength: 0.5, weights: [0.55, 0.95, 1, 0.35] },
  ai: { strength: 0.56, weights: [0.95, 1, 0.4, 0.55] },
  maker: { strength: 0.34, weights: [0.45, 0.65, 1, 0.5] },
  faq: { strength: 0.3, weights: [0.7, 0.55, 0.6, 0.9] },
  closing: { strength: 1, weights: [1, 0.95, 0.9, 0.75] },
} as const satisfies Record<string, Mood>;

/**
 * The light's moods: each section marked data-mood turns the light toward its mood while it fills the middle of the
 * screen. Until the page arrives the light stays down, and then comes up to whichever mood is current.
 */
export class Moods {
  private mood: Mood = moods.hero;
  private begun = false;
  private light: Light | null = null;

  constructor(stage: Stage) {
    const named = new Map<string, Mood>(Object.entries(moods));
    for (const section of all(document, "[data-mood]")) {
      const mood = named.get(section.dataset.mood ?? "");
      if (mood == null) continue;
      ScrollTrigger.create({
        trigger: section,
        start: "top 55%",
        end: "bottom 45%",
        onToggle: ({ isActive }) => {
          if (!isActive) return;
          this.mood = mood;
          if (this.begun) this.light?.mood(mood);
        },
      });
    }
    void stage.scenery.then((scenery) => {
      this.light = scenery?.light ?? null;
      if (this.begun) this.light?.mood(this.mood, 2.8);
    });
  }

  /** The light comes up, slowly, to the mood of wherever the page is. */
  begin(): void {
    this.begun = true;
    this.light?.mood(this.mood, 2.8);
  }
}

/**
 * What every page's frame does: the bar blurs what passes under it once the page has moved, the light keeps to the
 * sections' moods, what's marked for it moves in depth with the scroll (motion/depth.ts), buttons lean toward a
 * mouse, and the logo at the foot rises into view as the page ends. Returns the moods, for the page to bring the
 * light up once it has arrived.
 */
export function frame(stage: Stage): Moods {
  const nav = document.querySelector("[data-nav]");
  if (nav != null) {
    ScrollTrigger.create({ start: 24, end: "max", toggleClass: { targets: nav, className: "is-scrolled" } });
  }
  depth(stage);
  if (!stage.reduced && stage.fine) magnetize(all(document, "[data-magnetic]"));
  const logo = document.querySelector("[data-footer-logo]");
  if (logo != null && !stage.reduced) {
    gsap.fromTo(
      logo,
      { yPercent: 35 },
      { yPercent: 0, ease: "none", scrollTrigger: { trigger: logo, start: "top bottom", end: "max", scrub: true } },
    );
  }
  return new Moods(stage);
}
