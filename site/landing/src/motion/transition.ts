import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { pagePath, pages } from "../content/pages";
import { all, one } from "./dom";
import type { Stage } from "./stage";

/**
 * Where a leaving page tells the next about the way there, which /head.js reads before the next page paints: that
 * page opens under the curtain the last one ended on.
 */
const KEY = "rondo:curtain";

/** What moves as the curtain passes: the page's content, its foot, and the scenery drawn behind them. */
const CONTENT = "main, body > footer, [data-scenery]";

/** How long the curtain's front takes to cover the screen, in seconds. */
const COVER = 1.0;

interface Point {
  x: number;
  y: number;
}

/** The way to another page, for /head.js there: the page, and when it was left (Date.now()). */
interface Trip {
  to: string;
  at: number;
}

/** Where the curtain's edges always spread from, either way: the middle of the top of the screen. */
function top(): Point {
  return { x: window.innerWidth / 2, y: 0 };
}

/**
 * The curtain the site's pages open through, passing like a wave. Following a link to another of them, its front
 * (dark, with the page's light glowing in it) spreads down over the page from the middle of its top, inside a ring of
 * glass, the page sinking back beneath it, the next page's name rising in its middle. Its back is the next page,
 * spreading down through the curtain from the same place, inside a ring of its own, settling as it comes, as soon as
 * that page is ready. The front covers this page while the next loads, and the next opens under it, showing no blank
 * between. Any other page opens with the intro instead (motion/intro.ts). Links elsewhere (the docs, the app,
 * other sites, new tabs) open as usual, and with reduced motion so does every link.
 */
export class Curtain {
  private readonly sheet: HTMLElement;
  /** The ring along the curtain's front, coming over a page. */
  private readonly front: HTMLElement;
  /** The ring along its back, the next page coming through. */
  private readonly back: HTMLElement;
  private readonly label: HTMLElement;
  private readonly prefetched = new Set<string>();
  /** The way this page is being left, once it is. */
  private trip: Trip | null = null;
  /** The curtain's front coming over this page, as it's left. */
  private covering: gsap.core.Timeline | null = null;

  /** The page's curtain, or null on a page without one. (Under the curtain, <html> has a data-curtain of its own.) */
  static find(stage: Stage): Curtain | null {
    const root = document.body.querySelector<HTMLElement>("[data-curtain]");
    return root == null ? null : new Curtain(root, stage);
  }

  private constructor(
    private readonly root: HTMLElement,
    private readonly stage: Stage,
  ) {
    this.sheet = one(root, "[data-curtain-sheet]");
    this.front = one(root, "[data-curtain-front]");
    this.back = one(root, "[data-curtain-back]");
    this.label = one(root, "[data-curtain-label]");
  }

  /** Takes over clicks on links to the site's pages, and fetches a page as soon as a link to it is reached for. */
  listen(): void {
    if (this.stage.reduced) return;
    document.addEventListener("click", (event) => {
      if (event.defaultPrevented || event.button !== 0) return;
      if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      const url = this.destination(event.target);
      if (url == null) return;
      event.preventDefault();
      void this.leave(url);
    });
    const reaching = (event: Event) => {
      const url = this.destination(event.target);
      if (url != null) this.prefetch(url);
    };
    document.addEventListener("pointerover", reaching, { passive: true });
    document.addEventListener("touchstart", reaching, { passive: true });
    document.addEventListener("focusin", reaching);
    // Back to a page that was left under the curtain (the browser kept it as it was): it opens again.
    window.addEventListener("pageshow", (event) => {
      if (event.persisted && this.trip != null) {
        this.trip = null;
        this.covering?.progress(1, false);
        void this.lift();
      }
    });
  }

  /**
   * Opens the page under the curtain: the curtain's back, the page, spreads down through it from the middle of the
   * top, inside a ring of glass, settling from a little larger as it comes. Resolves as it starts, so what opens the
   * page plays as it comes through. At once without a curtain.
   */
  async lift(): Promise<void> {
    const html = document.documentElement;
    const state = html.dataset.curtain;
    if (state !== "cover") return;
    if (this.stage.reduced) {
      delete html.dataset.curtain;
      return;
    }
    const from = top();
    const content = all(document, CONTENT);
    const parts = [this.root, this.sheet, this.front, this.back, this.label];
    // The name may still be rising, with the front.
    gsap.killTweensOf(this.label);
    let measured = false;
    const remeasured = (): void => {
      measured = true;
    };
    ScrollTrigger.addEventListener("refresh", remeasured);
    await new Promise<void>((resolve) => {
      gsap
        .timeline({
          onComplete: () => {
            delete html.dataset.curtain;
            gsap.set(parts, { clearProps: "all" });
            this.sheet.style.removeProperty("mask-image");
            this.sheet.style.removeProperty("-webkit-mask-image");
            // Anything scrolling reveals that was measured as the page came through, settling from larger, is measured
            // again now the page is in place, or everything far down it would play late, and the last of it never.
            ScrollTrigger.removeEventListener("refresh", remeasured);
            if (measured) ScrollTrigger.refresh();
          },
        })
        .to(this.label, { scale: 0.86, opacity: 0, filter: "blur(10px)", duration: 0.4, ease: "power2.in" })
        .add(
          this.spread(this.back, from, 1.3, "power3.out", (radius) => {
            this.cut(from, radius, false);
          }),
          0.1,
        )
        .fromTo(
          content,
          { scale: 1.06, opacity: 1, transformOrigin: (_: number, target: Element) => this.origin(target, from) },
          { scale: 1, duration: 1.3, ease: "power3.out", clearProps: "transform,transformOrigin,opacity" },
          0.1,
        )
        .call(resolve, undefined, 0);
    });
  }

  /**
   * The curtain's front coming over, cutting what `shape` cuts to it as it spreads from `from`, and the next page's
   * name rising in its middle.
   */
  private coming(from: Point, shape: (radius: number) => void): gsap.core.Timeline {
    return gsap
      .timeline()
      .add(this.spread(this.front, from, COVER, "power3.out", shape), 0)
      .fromTo(
        this.label,
        { opacity: 0, y: 16, filter: "blur(8px)" },
        { opacity: 1, y: 0, filter: "blur(0px)", duration: 0.55, ease: "expo.out" },
        0.3,
      );
  }

  /**
   * One of the curtain's edges moving: a circle round `from` grows to the screen's far corner, `ring` along it, and
   * `shape` cuts whatever that edge cuts (the sheet, or the next page) to it, given its radius as it goes.
   */
  private spread(
    ring: HTMLElement,
    from: Point,
    duration: number,
    ease: string,
    shape: (radius: number) => void,
  ): gsap.core.Timeline {
    // Past the far corner, so the ring's glow is gone too.
    const reach =
      Math.hypot(Math.max(from.x, window.innerWidth - from.x), Math.max(from.y, window.innerHeight - from.y)) + 48;
    const wave = { at: 0 };
    const edge = (): void => {
      const radius = wave.at * reach;
      shape(radius);
      gsap.set(ring, { x: from.x - radius, y: from.y - radius, width: radius * 2, height: radius * 2 });
    };
    edge();
    return gsap
      .timeline()
      .to(wave, { at: 1, duration, ease, onUpdate: edge })
      .fromTo(ring, { autoAlpha: 0 }, { autoAlpha: 1, duration: 0.12, ease: "none" }, 0)
      .to(ring, { autoAlpha: 0, duration: 0.3, ease: "power1.in" }, duration * 0.7);
  }

  /** Masks the sheet to the circle of `radius` round `from` (`inside`), or to all but it. */
  private cut(from: Point, radius: number, inside: boolean): void {
    const at = `circle at ${from.x}px ${from.y}px`;
    const mask = inside
      ? `radial-gradient(${at}, #000 ${radius}px, transparent ${radius + 1.5}px)`
      : `radial-gradient(${at}, transparent ${radius}px, #000 ${radius + 1.5}px)`;
    this.sheet.style.setProperty("mask-image", mask);
    this.sheet.style.setProperty("-webkit-mask-image", mask);
  }

  /** `point` on the screen, from `target`'s corner, for it to scale about. */
  private origin(target: Element, point: Point): string {
    const box = target.getBoundingClientRect();
    return `${point.x - box.left}px ${point.y - box.top}px`;
  }

  /** The link `target` is in, as a page of this site other than this one; null for any other link, or none. */
  private destination(target: EventTarget | null): URL | null {
    const link = target instanceof Element ? target.closest("a") : null;
    if (!(link instanceof HTMLAnchorElement) || link.hasAttribute("download")) return null;
    if (link.target !== "" && link.target !== "_self") return null;
    const url = new URL(link.href);
    const path = pagePath(url.pathname);
    if (url.origin !== window.location.origin || !Object.hasOwn(pages, path)) return null;
    return path === pagePath(window.location.pathname) ? null : url;
  }

  /** Asks the browser to fetch a page it may soon open, once. */
  private prefetch(url: URL): void {
    if (this.prefetched.has(url.pathname)) return;
    this.prefetched.add(url.pathname);
    const hint = document.createElement("link");
    hint.rel = "prefetch";
    hint.href = url.href;
    document.head.append(hint);
  }

  /**
   * The way out: the curtain's front comes over the page, and once it covers the screen and the next page has loaded,
   * the next page opens under it.
   */
  private async leave(url: URL): Promise<void> {
    if (this.trip != null) return;
    const path = pagePath(url.pathname);
    this.trip = { to: path, at: Date.now() };
    this.label.textContent = pages[path] ?? "";
    await Promise.all([this.cover(), this.load(url)]);
    this.remember({ to: path, at: Date.now() });
    window.location.assign(url.href);
  }

  /** Tells the next page about the way there. */
  private remember(trip: Trip): void {
    try {
      sessionStorage.setItem(KEY, JSON.stringify(trip));
    } catch {
      // Without storage the next page simply opens, without the curtain over it.
    }
  }

  /** Fetches the next page into the browser's cache while the curtain comes over, giving up on a slow one. */
  private async load(url: URL): Promise<void> {
    const controller = new AbortController();
    const timeout = setTimeout(() => {
      controller.abort();
    }, 6000);
    try {
      const response = await fetch(url.href, { signal: controller.signal });
      await response.blob();
    } catch {
      // Offline, or slow: the browser says so, or gets there, once it's asked to open the page.
    } finally {
      clearTimeout(timeout);
    }
  }

  /**
   * The curtain's front spreads down over the page from the middle of its top, inside a ring of glass, the page
   * sinking back beneath it, the next page's name rising in its middle. Resolves once it covers the screen.
   */
  private cover(): Promise<void> {
    const html = document.documentElement;
    const from = top();
    const content = all(document, CONTENT);
    html.dataset.curtain = "leaving";
    return new Promise((resolve) => {
      this.covering = this.coming(from, (radius) => {
        this.cut(from, radius, true);
      })
        .eventCallback("onComplete", () => {
          html.dataset.curtain = "cover";
          resolve();
        })
        .to(
          content,
          {
            scale: 0.96,
            opacity: 0.6,
            transformOrigin: (_: number, target: Element) => this.origin(target, from),
            duration: COVER,
            ease: "power3.out",
          },
          0,
        );
    });
  }
}
