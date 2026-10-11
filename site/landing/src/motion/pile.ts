import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { collection } from "../content/cards";
import { all, one, onResize } from "./dom";
import { whileOnScreen } from "./loop";
import { clamp, damp, lerp, quantize } from "./math";
import type { Stage } from "./stage";

/** How many cards stay in front of the deck, a little askew, before they're hidden in its body. */
const FRONT = 8;
/** The deck's body at its thickest, for the whole collection, as a share of a card's width. */
const THICKEST = 0.32;

/**
 * The stacking's tempo. The first card leaves at once and the next `gap` later, each gap `quicken` of the last until
 * they're `fastest` apart; cards then pour in at that pace for `torrent` seconds, and the gaps widen again by `ease`
 * until they're `slowest` apart, when the last card leaves. Flights shorten from `slowFlight` to `fastFlight` as the
 * pace rises: long enough, at any pace, for many cards to be settling at once.
 */
const tempo = {
  gap: 0.5,
  quicken: 0.82,
  fastest: 0.026,
  torrent: 2.2,
  ease: 1.32,
  slowest: 0.4,
  slowFlight: 2.4,
  fastFlight: 1.7,
} as const;

const formatter = new Intl.NumberFormat("en");

/**
 * How a card comes to the deck, fast at first and slower and slower: across to it quickly (`arrive`), and onto its
 * face slowly (`press`), so for most of its flight a card hovers just in front of the deck, settling onto it, as
 * many others do at the same time. Its turn straightens in between (`straighten`).
 */
const arrive = (t: number) => 1 - (1 - t) ** 5;
const press = (t: number) => 1 - (1 - t) ** 2.2;
const straighten = (t: number) => 1 - (1 - t) ** 3;

/** Where a card is, from the deck's place: in pixels, its size against the deck's, and turned, in degrees. */
interface Pose {
  x: number;
  y: number;
  scale: number;
  rotate: number;
}

/** A card's way to the deck: when it left, how long it takes, its path (a curve through `bend`) and its turn. */
interface Flight {
  readonly start: number;
  readonly duration: number;
  readonly from: Pose;
  readonly bend: { readonly x: number; readonly y: number };
  readonly to: { readonly x: number; readonly y: number; readonly rotate: number };
}

/** A card element: on its way to the deck, in front of it (`depth` cards down), or waiting off it. */
interface Card {
  readonly element: HTMLElement;
  flight: Flight | null;
  depth: number;
  readonly lying: Pose;
  readonly rest: { x: number; y: number; rotate: number };
  shown: boolean;
  layer: number;
}

/** A card the deck is waiting to send: when, from which corner, how long its flight, and whether it's the last. */
interface Launch {
  readonly at: number;
  readonly side: -1 | 1;
  readonly duration: number;
  readonly last?: true;
}

/**
 * The collection section's deck, standing upright at the foot of the screen. As the section comes into view, cards
 * come to it from beyond the screen's top corners: one, then another, then faster and faster until dozens are in the
 * air at once. Each comes fast and slows the whole way down, so it settles onto the deck's face rather than hitting
 * it, and many are settling at any moment; the cards in front lie a little askew and square up as more arrive. The
 * deck's body grows thicker behind them and the count beside climbs with every card, faster than they can be seen,
 * to tens of thousands. Then a card arrives now and then. With reduced motion the deck is simply there, stacked.
 */
export class Pile {
  private readonly section: HTMLElement;
  private readonly deck: HTMLElement;
  private readonly block: HTMLElement;
  private readonly floor: HTMLElement;
  private readonly count: HTMLElement;
  private readonly cards: Card[];
  private readonly last: Card;
  private readonly queue: Launch[] = [];
  private next = 0;
  private layer = 1;
  private time = 0;
  private landed = 0;
  private sent = 0;
  private planned = 0;
  private counted = 0;
  private display = 0;
  private shownCount = -1;
  private thickness = -1;
  private width = 0;
  private stacking = false;
  private idle: gsap.core.Tween | null = null;

  constructor(root: HTMLElement, stage: Stage) {
    this.section = root.closest("section") ?? root;
    this.deck = one(root, ".pile__deck");
    this.block = one(root, "[data-pile-block]");
    this.floor = one(root, "[data-pile-floor]");
    this.count = one(this.section, "[data-pile-count]");
    const cards = all(root, "[data-pile-card]").map((element): Card => ({
      element,
      flight: null,
      depth: -1,
      lying: { x: 0, y: 0, scale: 1, rotate: 0 },
      rest: { x: 0, y: 0, rotate: 0 },
      shown: false,
      layer: 0,
    }));
    const last = cards.pop();
    if (last == null) throw new Error("The collection has no cards");
    this.cards = cards;
    this.last = last;
    root.classList.add("is-live");
    this.measure();
    onResize(root, () => this.measure());

    if (stage.reduced) {
      this.place();
      return;
    }
    this.empty();
    ScrollTrigger.create({ trigger: this.section, start: "top 18%", once: true, onEnter: () => this.stack() });
    whileOnScreen(this.section, this.tick);
  }

  private measure(): void {
    this.width = this.deck.offsetWidth;
    this.thickness = -1;
  }

  /** Nothing on the deck yet, and the count at nothing. */
  private empty(): void {
    for (const card of [...this.cards, this.last]) {
      card.flight = null;
      card.depth = -1;
      this.show(card, false);
    }
    this.landed = 0;
    this.sent = 0;
    this.counted = 0;
    this.display = 0;
    gsap.set(this.block, { opacity: 0 });
  }

  /** The deck as it is once stacked, at once: with reduced motion. */
  private place(): void {
    this.counted = this.display = collection;
    const resting = [...this.cards.slice(-(FRONT / 2 - 1)), this.last];
    resting.forEach((card, index) => {
      const depth = resting.length - 1 - index;
      card.depth = depth;
      const side = index % 2 ? 1 : -1;
      Object.assign(
        card.rest,
        depth === 0 ? { x: 0, y: 0, rotate: -0.8 } : { x: side * 5, y: -side * 2, rotate: side * 2 },
      );
      Object.assign(card.lying, { ...card.rest, scale: 1 });
      card.layer = index + 1;
      card.element.style.zIndex = String(card.layer);
      this.show(card, true);
    });
    gsap.set(this.block, { opacity: 1 });
    this.draw(0);
  }

  /** Plans the stacking: gaps narrowing to a torrent, then widening again until the last card. */
  private stack(): void {
    this.stacking = true;
    const start = this.time + 0.1;
    let leaving = 0;
    let gap: number = tempo.gap;
    let side: -1 | 1 = -1;
    let torrentEnds = Infinity;
    let slowing = false;
    const launches: Launch[] = [];
    for (;;) {
      const pace = clamp((tempo.gap - gap) / (tempo.gap - tempo.fastest), 0, 1);
      const duration = lerp(tempo.slowFlight, tempo.fastFlight, pace);
      launches.push({ at: start + leaving, side, duration });
      // Turn about while it's slow; in the torrent, mostly.
      side = pace < 0.8 || Math.random() < 0.65 ? (side === -1 ? 1 : -1) : side;
      if (!slowing) {
        gap = Math.max(tempo.fastest, gap * tempo.quicken);
        if (gap === tempo.fastest && torrentEnds === Infinity) torrentEnds = leaving + tempo.torrent;
        if (leaving >= torrentEnds) slowing = true;
      } else {
        gap *= tempo.ease;
        if (gap >= tempo.slowest) break;
      }
      leaving += gap;
    }
    launches.push({ at: start + leaving + tempo.slowest, side, duration: tempo.slowFlight, last: true });
    this.queue.push(...launches);
    this.planned = launches.length;
  }

  private readonly tick = (_time: number, deltaMs: number): void => {
    const seconds = Math.min(deltaMs / 1000, 1 / 20);
    this.time += seconds;
    while (this.queue[0] != null && this.queue[0].at <= this.time) {
      const launch = this.queue.shift();
      if (launch != null) this.launch(launch);
    }
    this.draw(seconds);
  };

  /** Sends a card off from beyond a top corner of the screen, bigger (nearer) and turned a little. */
  private launch({ at, side, duration, last }: Launch): void {
    const card = last ? this.last : this.free();
    if (card == null) return;
    const deck = this.deck.getBoundingClientRect();
    const centre = { x: deck.left + deck.width / 2, y: deck.top + deck.height / 2 };
    const viewport = document.documentElement.clientWidth;
    // From above the screen's top (or the section's, if that's higher), a little in from the corner or beyond it.
    const top = Math.min(0, this.section.getBoundingClientRect().top);
    const from: Pose = {
      x: (side === -1 ? 0 : viewport) - centre.x + side * this.width * (Math.random() * 0.5 - 0.15),
      y: top - centre.y - deck.height * (0.6 + Math.random() * 0.5),
      scale: 1.3 + Math.random() * 0.35,
      rotate: -side * (6 + Math.random() * 12),
    };
    const to = last
      ? { x: 0, y: 0, rotate: -0.8 }
      : {
          x: (Math.random() - 0.5) * this.width * 0.06,
          y: (Math.random() - 0.5) * this.width * 0.03,
          rotate: (Math.random() - 0.5) * 6,
        };
    // The way bends a little, coming down more steeply at the end.
    card.flight = { start: at, duration, from, bend: { x: lerp(from.x, to.x, 0.45), y: lerp(from.y, to.y, 0.2) }, to };
    card.depth = -1;
    card.layer = this.layer++;
    card.element.style.zIndex = String(card.layer);
    this.show(card, true);
    // The count climbs as cards leave for the deck, so it races with the stream rather than behind it.
    this.sent++;
    this.counted =
      this.stacking && this.planned > 0
        ? Math.round(collection ** (Math.min(this.sent, this.planned) / this.planned))
        : this.counted + 1;
  }

  /** A card that isn't on its way or in front of the deck: the next in turn, so their faces come round in order. */
  private free(): Card | null {
    const count = this.cards.length;
    for (let tries = 0; tries < count; tries++) {
      const card = this.cards[this.next % count];
      this.next++;
      if (card != null && card.flight == null && card.depth < 0) return card;
    }
    return null;
  }

  /** A card has settled in front: the cards behind it square up, the deck grows, the count climbs. */
  private land(card: Card): void {
    const flight = card.flight;
    if (flight == null) return;
    card.flight = null;
    for (const other of [...this.cards, this.last]) {
      if (other.depth < 0) continue;
      other.depth++;
      other.rest.x *= 0.6;
      other.rest.y *= 0.6;
      other.rest.rotate *= 0.55;
      if (other.depth >= FRONT) {
        other.depth = -1;
        this.show(other, false);
      }
    }
    card.depth = 0;
    Object.assign(card.rest, flight.to);
    Object.assign(card.lying, { ...flight.to, scale: 1 });
    if (this.landed === 0) gsap.to(this.block, { opacity: 1, duration: 0.6, ease: "power2.out" });
    this.landed++;
    if (card === this.last) this.done();
  }

  /** The last card has settled: the count is the whole collection, and from now on a card arrives now and then. */
  private done(): void {
    this.stacking = false;
    this.counted = Math.max(this.counted, collection);
    gsap.fromTo(this.floor, { opacity: 0.75 }, { opacity: 0.5, duration: 1.6, ease: "power2.out" });
    let side: -1 | 1 = 1;
    const arrive = (): void => {
      this.queue.push({ at: this.time, side, duration: tempo.slowFlight });
      side = side === 1 ? -1 : 1;
      this.idle = gsap.delayedCall(2.2 + Math.random() * 1.6, arrive);
    };
    this.idle?.kill();
    this.idle = gsap.delayedCall(2.4, arrive);
  }

  private show(card: Card, shown: boolean): void {
    if (card.shown === shown) return;
    card.shown = shown;
    card.element.style.opacity = shown ? "1" : "0";
  }

  private draw(seconds: number): void {
    for (const card of [...this.cards, this.last]) {
      if (card.flight != null) this.fly(card, card.flight);
      else if (card.depth >= 0) this.lie(card, seconds);
    }
    this.display = damp(this.display, this.counted, 10, seconds);
    if (Math.abs(this.counted - this.display) < 0.5) this.display = this.counted;
    const shown = Math.round(this.display);
    if (shown !== this.shownCount) {
      this.count.textContent = formatter.format(shown);
      this.shownCount = shown;
    }
    const thickness = quantize(this.width * THICKEST * (Math.log1p(shown) / Math.log1p(collection)), 0.5);
    if (thickness !== this.thickness) {
      this.block.style.setProperty("--thick", `${thickness}px`);
      this.thickness = thickness;
    }
  }

  /**
   * A card on its way: along its curve to the deck quickly, then nearer and nearer its face, shrinking to the deck's
   * size as it settles onto it.
   */
  private fly(card: Card, flight: Flight): void {
    const t = clamp((this.time - flight.start) / flight.duration, 0, 1);
    if (t >= 1) {
      this.land(card);
      this.lie(card, 0);
      return;
    }
    const u = arrive(t);
    const { from, bend, to } = flight;
    const along = (a: number, b: number, c: number) => (1 - u) ** 2 * a + 2 * u * (1 - u) * b + u ** 2 * c;
    this.pose(
      card,
      {
        x: along(from.x, bend.x, to.x),
        y: along(from.y, bend.y, to.y),
        scale: lerp(from.scale, 1, press(t)),
        rotate: lerp(from.rotate, to.rotate, straighten(t)),
      },
      Math.min(1, t / 0.05),
    );
  }

  /** A card in front of the deck, squaring up as more arrive. */
  private lie(card: Card, seconds: number): void {
    const { lying, rest } = card;
    lying.x = damp(lying.x, rest.x, 8, seconds);
    lying.y = damp(lying.y, rest.y, 8, seconds);
    lying.rotate = damp(lying.rotate, rest.rotate, 8, seconds);
    this.pose(card, lying, 1);
  }

  private pose(card: Card, { x, y, scale, rotate }: Pose, opacity: number): void {
    const style = card.element.style;
    style.transform =
      `translate3d(${x.toFixed(2)}px, ${y.toFixed(2)}px, 0) ` +
      `rotate(${rotate.toFixed(2)}deg) scale(${scale.toFixed(4)})`;
    style.opacity = opacity >= 1 ? "1" : opacity.toFixed(3);
  }
}
