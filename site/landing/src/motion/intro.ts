import { gsap } from "gsap";
import { one } from "./dom";

/**
 * The opening of a page no link of the site's opened (components/Intro.astro). Rondo's mark appears in a sweep that
 * turns counter-clockwise, as its arrow does, and the camera pulls back from it: the mark shrinks away into the
 * distance, ever faster, and blurs, while the white gives way to the ring of cards the camera is pulling back through
 * (motion/scenery/ring.ts). On a visit's first page the two are apart: the mark sweeps in with nothing else running so
 * it stays smooth, then holds, still, a small spinner turning under it, while the page readies everything it will draw
 * (the scenery's shaders and pictures, the flight's film), so none of that work is ever seen. On a page opened again,
 * which readies quickly, the page readies under the white, and then the mark flies away as it sweeps in.
 */
export class Intro {
  /** Resolves once the page can take heavy work: once the mark has swept in on a visit's first page, at once again. */
  readonly entered: Promise<void>;
  private readonly mark: HTMLElement;
  private enter: () => void = () => undefined;

  /** The intro, on a page that opens with it (/head.js's data-intro); null on any other. */
  static find(): Intro | null {
    const opening = document.documentElement.dataset.intro;
    if (opening == null) return null;
    // <html> has a data-intro of its own.
    const root = document.body.querySelector<HTMLElement>("[data-intro]");
    return root == null ? null : new Intro(root, opening === "again");
  }

  private constructor(
    private readonly root: HTMLElement,
    private readonly again: boolean,
  ) {
    this.mark = one(root, "[data-intro-mark]");
    this.entered = again
      ? Promise.resolve()
      : new Promise((resolve) => {
          this.enter = resolve;
        });
  }

  /** On a visit's first page, the mark sweeps into view out of a blur and settles; resolves once it has. */
  async hold(): Promise<void> {
    if (this.again) return;
    await new Promise<void>((resolve) => {
      this.sweep(1.35, "power3.inOut")
        .fromTo(
          this.mark,
          { rotate: 24, scale: 0.86, filter: "blur(10px)" },
          { rotate: 0, scale: 1, filter: "blur(0px)", duration: 1.35, ease: "power3.inOut" },
          0,
        )
        .eventCallback("onComplete", resolve);
    });
    this.enter();
  }

  /** The mark flies away, sweeping in as it goes on a page opened again, while the white fades to show the page. */
  leave(): void {
    const html = document.documentElement;
    if (html.dataset.intro == null) return;
    html.dataset.intro = "leaving";
    const leaving = gsap
      .timeline({
        onComplete: () => {
          delete html.dataset.intro;
          gsap.set([this.root, this.mark], { clearProps: "all" });
        },
      })
      .to(this.mark, { scale: 0.12, duration: 1.3, ease: "power2.in" }, 0)
      .fromTo(this.mark, { filter: "blur(0px)" }, { filter: "blur(10px)", duration: 0.95, ease: "power1.in" }, 0.35)
      .to(this.mark, { opacity: 0, duration: 0.4, ease: "power1.in" }, 0.9)
      .fromTo(this.root, { "--white": 1 }, { "--white": 0, duration: 0.6, ease: "power1.inOut" }, 0.1);
    if (this.again) leaving.add(this.sweep(0.8, "power2.out"), 0);
  }

  /** The mark sweeps into view through a turning cut, as its arrow turns. */
  private sweep(duration: number, ease: string): gsap.core.Timeline {
    return gsap
      .timeline()
      .set(this.mark, { visibility: "visible" })
      .fromTo(this.mark, { "--sweep": "0deg" }, { "--sweep": "384deg", duration, ease });
  }
}
