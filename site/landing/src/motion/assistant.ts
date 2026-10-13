import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { requests } from "../content/assistants";
import type { Field } from "./assistant/field";
import { all, one, onResize } from "./dom";
import { loopInView } from "./loop";
import type { Stage } from "./stage";

/** How long the shockwave takes to cross one place in the row. */
const PLACE = 0.16;
/** Where the shockwave sets out from, off the row so it comes in, and how far past the last place it goes, in places. */
const BEFORE = 1.6;
const AFTER = 0.8;
/** How far short of a card's left edge the shockwave's crest is when its swell reaches the card, in places. */
const REACH = 0.4;
/** How far to the left of their places the cards are, in screen widths, before they slide in, as the shockwave carries them. */
const ARRIVING = 0.35;
/** How long each shockwave takes to carry the row a place along. */
const CARRYING = 2.2;
/**
 * How a push goes: the row picks up speed for the first share of it (`HIT`), then friction slows it to a stop
 * (`FRICTION`, per unit of the push), landing exactly where it's carried to.
 */
const HIT = 0.14;
const FRICTION = 6;

/**
 * The AI section, told on a loop while it's in view, one request at a time. The headline ends on an empty line where
 * the caret blinks, over a row of places for cards, windows onto one surface (motion/assistant/field.ts). What's asked
 * is typed out, stirring it; the caret blinks on, a light reads through the words and the surface swells; then a
 * shockwave crosses the row at an even pace (the row's --sweep), and each card shows itself as the swell reaches
 * it; the assistant that made them is named under the row. A while later the words are selected and gone, the
 * cards lift away, and the next request begins. Before any of it, as the row scrolls in, the cards slide into their
 * places from the left, the way the shockwave carries them, already moving and slowing to a stop, one a beat after another; and each shockwave carries
 * the row a place along, where it stays, a card going round from its far end to its near one. With reduced motion the
 * section simply rests as it's drawn.
 */
export class Assistant {
  private readonly line: HTMLElement;
  private readonly typed: HTMLElement;
  private readonly select: HTMLElement;
  private readonly row: HTMLElement;
  /** What's made in each place, in the row's order, and each request's card in each of them. */
  private made: HTMLElement[] = [];
  private faces: HTMLElement[][] = [];
  private readonly by: HTMLElement;
  private readonly byName: HTMLElement;
  /** The surface the empty places show (motion/assistant/field.ts), once it's there; never without WebGL. */
  private field: Field | null = null;
  /** The row's places, and how far along the shockwaves have carried them, in places. */
  private readonly places: HTMLElement;
  private readonly carried = { value: 0 };
  /** The request whose cards are made; null while the places are empty. */
  private shown: number | null = null;
  private next = 0;

  constructor(root: HTMLElement, stage: Stage) {
    this.line = one(root, ".typed");
    this.typed = one(root, "[data-typed]");
    this.select = one(root, "[data-typed-select]");
    this.row = one(root, "[data-row]");
    this.places = one(root, ".row__places");
    this.order();
    this.by = one(root, "[data-by]");
    this.byName = one(root, "[data-by-name]");
    if (stage.reduced) return;
    // Empty places, and nothing typed yet but the caret.
    root.classList.add("is-live");
    gsap.set(this.made, { autoAlpha: 0 });
    gsap.set(this.faces.flat(), { autoAlpha: 0 });
    gsap.set(this.row, { "--sweep": -BEFORE });
    gsap.set(this.by, { autoAlpha: 0 });
    const arriving = all(root, "[data-place]");
    gsap.set(arriving, { x: () => -window.innerWidth * ARRIVING, force3D: true });
    this.typed.textContent = "";
    this.line.classList.add("is-waiting");
    // In development, with ?tune in the address, a panel for tuning the sea and the shockwave.
    if (import.meta.env.DEV && new URLSearchParams(location.search).has("tune"))
      void import("./assistant/tuning").then(({ Tuning }) => new Tuning());
    // Not before the page is on screen, so nothing's typed under the intro.
    void stage.arrived.then(async () => {
      ScrollTrigger.create({
        trigger: this.row,
        start: "top 90%",
        once: true,
        onEnter: () => {
          gsap.to(arriving, { x: 0, duration: 3, ease: "power3.out", stagger: 0.09, force3D: true });
        },
      });
      loopInView(root, () => this.story(), { start: "top 70%", end: "bottom 15%" });
      onResize(this.places, () => this.place());
      const { Field } = await import("./assistant/field");
      this.field = Field.create(this.row, all(root, "[data-field]", HTMLCanvasElement));
    });
  }

  private story(): gsap.core.Timeline {
    const index = this.next;
    const request = requests[index];
    const timeline = gsap.timeline({ defaults: { immediateRender: false } });
    if (request == null) return timeline;
    this.next = (index + 1) % requests.length;

    // The last request's words are selected and gone, and its cards lift away; the caret waits.
    let at = 0;
    const shown = this.shown;
    if (shown != null) {
      timeline.fromTo(
        this.select,
        { backgroundSize: "0% 100%" },
        { backgroundSize: "100% 100%", duration: 0.3, ease: "power2.inOut" },
        0,
      );
      this.empty(timeline, this.faces[shown] ?? [], 0.3);
      timeline.call(
        () => {
          this.typed.textContent = "";
          gsap.set(this.select, { backgroundSize: "0% 100%" });
          this.line.classList.add("is-waiting");
        },
        undefined,
        0.45,
      );
      at = 0.45;
    }
    this.shown = null;

    // What's asked, typed a letter at a time.
    at += 0.6;
    timeline.call(
      () => {
        this.line.classList.remove("is-waiting");
        this.line.classList.add("is-typing");
        this.field?.listen();
      },
      undefined,
      at,
    );
    for (let letters = 1; letters <= request.words.length; letters++) {
      const words = request.words.slice(0, letters);
      timeline.call(() => (this.typed.textContent = words), undefined, at);
      at += this.pause(request.words, letters);
    }

    // Sent: the caret blinks on, a light reads through the words and the surface swells; as it leaves them, the
    // shockwave crosses the row.
    const sent = at + 0.2;
    timeline.call(
      () => {
        this.line.classList.replace("is-typing", "is-waiting");
        this.typed.classList.add("is-reading");
        this.shown = index;
        this.field?.think();
      },
      undefined,
      sent,
    );
    timeline.call(() => this.typed.classList.remove("is-reading"), undefined, sent + 1.05);
    const made = this.fill(timeline, index, sent + 0.4);
    timeline.call(() => this.field?.cover(), undefined, made);

    // A while to take them in.
    timeline.set({}, {}, made + 2.6);
    return timeline;
  }

  /** What's made in each place and each request's card in it, in the order the row has them now. */
  private order(): void {
    this.made = all(this.places, "[data-made]");
    this.faces = requests.map((_, index) => this.made.flatMap((made) => all(made, "[data-face]")[index] ?? []));
  }

  /** Puts the row's places where they've been carried, and the surface under them where the screen has it. */
  private place(): void {
    const [first, second] = all(this.places, "[data-place]");
    const width = (second?.offsetLeft ?? 0) - (first?.offsetLeft ?? 0);
    gsap.set(this.places, { x: this.carried.value * width, force3D: true });
    this.field?.slide(this.carried.value);
  }

  /**
   * Once the row has been carried a whole place, the card that went off its far end comes round to its near one, and
   * the row steps back a place under it, so nothing on screen moves.
   */
  private wrap(): void {
    if (this.carried.value < 1 - 1e-3) return;
    const last = this.places.lastElementChild;
    if (last != null) this.places.prepend(last);
    this.carried.value -= 1;
    this.order();
    this.field?.measure();
    this.place();
  }

  /**
   * How far along its push the row is at `progress` through it, from 0 to 1: its speed builds over the first `HIT` of
   * it, then friction takes it down, so it comes to rest just as it arrives.
   */
  private carrying(progress: number): number {
    const coasting = (1 - Math.exp(-FRICTION * (1 - HIT))) / FRICTION;
    const total = HIT / 2 + coasting;
    if (progress < HIT) return (progress * progress) / (2 * HIT) / total;
    return (HIT / 2 + (1 - Math.exp(-FRICTION * (progress - HIT))) / FRICTION) / total;
  }

  /** How long the letter at `index` takes to type: unevenly, as people type, and a beat after each word. */
  private pause(text: string, index: number): number {
    const jitter = ((index * 37) % 11) / 330;
    return 0.03 + jitter + (text[index - 1] === " " ? 0.05 : 0);
  }

  /**
   * Makes request `index`'s cards from `at`: the shockwave comes in from the row's left and crosses it, and each card
   * shows itself as the swell reaches it, coming into focus and settling onto its place. Returns when it has crossed.
   */
  private fill(timeline: gsap.core.Timeline, index: number, at: number): number {
    const columns = Number(getComputedStyle(this.row).getPropertyValue("--n")) || 1;
    const end = columns + AFTER;
    const crossing = (end + BEFORE) * PLACE;
    // When the light is `place` places across.
    const when = (place: number): number => at + (place + BEFORE) * PLACE;
    timeline.set(this.faces[index] ?? [], { autoAlpha: 1 }, at);
    timeline.call(() => (this.byName.textContent = requests[index]?.by ?? ""), undefined, at);
    timeline.fromTo(this.row, { "--sweep": -BEFORE }, { "--sweep": end, duration: crossing, ease: "none" }, at);
    // As it reaches the middle of the row it carries the row along with it, a place, where it stays.
    timeline.call(
      () => {
        gsap.to(this.carried, {
          value: Math.round(this.carried.value) + 1,
          duration: CARRYING,
          ease: (progress: number) => this.carrying(progress),
          onUpdate: () => this.place(),
          onComplete: () => this.wrap(),
        });
      },
      undefined,
      when((columns - 1) / 2 - 1),
    );
    this.made.forEach((made, place) => {
      timeline.fromTo(
        made,
        { autoAlpha: 0, y: made.offsetWidth * 0.03, scale: 0.985, filter: "blur(12px)" },
        { autoAlpha: 1, y: 0, scale: 1, filter: "blur(0px)", duration: 0.7, ease: "expo.out" },
        when(place - REACH),
      );
    });
    timeline.fromTo(
      this.by,
      { autoAlpha: 0, y: 6 },
      { autoAlpha: 1, y: 0, duration: 0.9, ease: "expo.out" },
      when(columns),
    );
    return when(end);
  }

  /** Takes `faces` away from `at`: the cards lift off their places and fade, from the left, leaving them empty. */
  private empty(timeline: gsap.core.Timeline, faces: HTMLElement[], at: number): void {
    timeline.to(this.by, { autoAlpha: 0, duration: 0.3, ease: "power1.in" }, at - 0.2);
    // The surface under them carries on as the shockwave left it: it's settled everywhere, so taking the shockwave
    // off the row now changes nothing in it, and it calms on from there.
    timeline.call(() => this.field?.uncover(), undefined, at);
    timeline.set(this.row, { "--sweep": -BEFORE }, at);
    timeline.to(
      this.made,
      {
        autoAlpha: 0,
        y: (_: number, made: HTMLElement) => -made.offsetWidth * 0.06,
        filter: "blur(6px)",
        duration: 0.5,
        ease: "power2.in",
        stagger: 0.05,
      },
      at,
    );
    const gone = at + 0.5 + 0.05 * (this.made.length - 1);
    timeline.set(faces, { autoAlpha: 0 }, gone);
  }
}
