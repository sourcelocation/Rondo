import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { SplitText } from "gsap/SplitText";
import { Aurora, isMood, type Mood } from "./aurora";
import { all, hasFinePointer, one, prefersReducedMotion } from "./dom";
import { HistoryScene } from "./history";
import { Intro } from "./intro";
import { CodeLens } from "./lens";
import { magnetize } from "./magnetic";
import { Marquee } from "./marquee";
import { Reveals } from "./reveal";
import { playPlatforms } from "./platforms";
import { CardRing } from "./ring";
import { SmoothScroll } from "./scroll";
import { SyncScene } from "./sync";
import { WorkPreview } from "./work";

gsap.registerPlugin(ScrollTrigger, SplitText);

/** Waits for the page's fonts, which text is split by, but not for long. */
async function fontsLoaded(): Promise<void> {
  await Promise.race([document.fonts.ready, new Promise((resolve) => setTimeout(resolve, 1500))]);
}

/** Waits until the page is on screen, so a tab opened in the background plays its intro when it's first seen. */
async function pageShown(): Promise<void> {
  if (document.visibilityState === "visible") return;
  await new Promise<void>((resolve) => {
    const shown = () => {
      if (document.visibilityState !== "visible") return;
      document.removeEventListener("visibilitychange", shown);
      resolve();
    };
    document.addEventListener("visibilitychange", shown);
  });
}

/**
 * The landing page's motion, put together: smooth scrolling, the light behind the page and its moods, the intro,
 * the hero's ring, every section's scene, and the reveals. With reduced motion the page is shown in place, and only
 * what someone does moves anything.
 */
export class Landing {
  private readonly reduced = prefersReducedMotion();
  private readonly scroll = new SmoothScroll(!this.reduced);
  private readonly aurora = Aurora.mount(one(document, "[data-aurora]"), this.reduced);
  private readonly reveals = new Reveals(document, this.reduced);
  private readonly hero = one(document, "[data-hero]");
  private readonly title = one(this.hero, "[data-hero-title]");
  private readonly ring = new CardRing(
    one(this.hero, "[data-ring]"),
    this.title,
    () => this.scroll.velocity,
    this.reduced,
  );
  private mood: Mood = "hero";
  private arrived = false;

  async start(): Promise<void> {
    await fontsLoaded();
    // A page slow to get here skips its intro; one waiting in a background tab hasn't kept anyone waiting.
    const quick = performance.now() < 2600;
    this.reveals.start();
    this.scenes();
    this.moods();
    this.bar();
    this.leaving();
    this.footer();
    await pageShown();

    if (this.reduced) {
      this.ring.place();
      this.arrive();
      this.reveals.playAll();
      return;
    }
    const logo = one(document, ".nav__home");
    if (Intro.wanted(quick)) {
      new Intro(one(document, "[data-intro]"), logo).play(() => this.arrive());
    } else {
      this.reveals.play(logo);
      this.arrive();
    }
  }

  /** The hero arrives: the light comes up, the cards are dealt, and the words come in after each other. */
  private arrive(): void {
    this.arrived = true;
    this.aurora?.mood(this.mood, 2.8);
    if (this.reduced) return;
    this.ring.deal();
    const steps: [number, Element | null][] = [
      [0.15, this.title],
      [0.85, one(this.hero, ".hero__lead")],
      [1.15, one(this.hero, ".hero__entry")],
      ...all(document, ".nav__side").map((side): [number, Element] => [1.3, side]),
    ];
    for (const [delay, element] of steps) gsap.delayedCall(delay, () => this.reveals.play(element));
  }

  private scenes(): void {
    const speed = () => this.scroll.velocity;
    new HistoryScene(one(document, "[data-history]"), this.reduced);
    if (!this.reduced) {
      for (const row of all(document, "[data-marquee]")) {
        new Marquee(row, row.dataset.marquee === "right" ? -1 : 1, speed);
      }
    }
    new CodeLens(one(document, "[data-lens]"), this.reduced);
    new SyncScene(one(document, "[data-merge]"), this.reduced);
    playPlatforms(one(document, "[data-trio]"), this.reduced);
    new WorkPreview(one(document, "[data-work]"), one(document, "[data-preview]"), this.reduced);
    if (!this.reduced && hasFinePointer()) magnetize(all(document, "[data-magnetic]"));
  }

  /** Each section turns the light toward its mood while it fills the middle of the screen. */
  private moods(): void {
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
          if (this.arrived) this.aurora?.mood(mood);
        },
      });
    }
  }

  /** The bar blurs what passes under it once the page has moved. */
  private bar(): void {
    ScrollTrigger.create({
      start: 24,
      end: "max",
      toggleClass: { targets: one(document, "[data-nav]"), className: "is-scrolled" },
    });
  }

  /**
   * Scrolling away from the hero, the ring opens and fades, and the headline drifts up into a blur. What's under it
   * (the lead and the way in) simply scrolls away, as clickable as anything else until it's gone.
   */
  private leaving(): void {
    ScrollTrigger.create({
      trigger: this.hero,
      start: "top bottom",
      end: "bottom top",
      onToggle: ({ isActive }) => (this.ring.visible = isActive),
    });
    ScrollTrigger.create({
      trigger: this.hero,
      start: "top top",
      end: "bottom top",
      onUpdate: ({ progress }) => (this.ring.leaving = progress),
    });
    if (this.reduced) return;
    gsap.to(this.title, {
      yPercent: -16,
      scale: 0.94,
      opacity: 0,
      filter: "blur(18px)",
      ease: "power1.in",
      scrollTrigger: { trigger: this.hero, start: "top top", end: "bottom top", scrub: 0.6 },
    });
  }

  /** The logo at the foot rises into view as the page ends. */
  private footer(): void {
    if (this.reduced) return;
    gsap.fromTo(
      one(document, "[data-footer-logo]"),
      { yPercent: 35 },
      {
        yPercent: 0,
        ease: "none",
        scrollTrigger: { trigger: "[data-footer-logo]", start: "top bottom", end: "max", scrub: true },
      },
    );
  }
}
