import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { isMood, type Mood } from "./aurora";
import { depth } from "./depth";
import { all } from "./dom";
import { magnetize } from "./magnetic";
import type { Stage } from "./stage";

/**
 * The light's moods: each section marked data-mood turns the light toward its mood while it fills the middle of the
 * screen. Until the page arrives the light stays down, and then comes up to whichever mood is current.
 */
export class Moods {
  private mood: Mood = "hero";
  private begun = false;

  constructor(private readonly stage: Stage) {
    for (const section of all(document, "[data-mood]")) {
      const mood = section.dataset.mood;
      if (!isMood(mood)) continue;
      ScrollTrigger.create({
        trigger: section,
        start: "top 55%",
        end: "bottom 45%",
        onToggle: ({ isActive }) => {
          if (!isActive) return;
          this.mood = mood;
          if (this.begun) this.stage.aurora?.mood(mood);
        },
      });
    }
  }

  /** The light comes up, slowly, to the mood of wherever the page is. */
  begin(): void {
    this.begun = true;
    this.stage.aurora?.mood(this.mood, 2.8);
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
