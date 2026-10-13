import { ScrollTrigger } from "gsap/ScrollTrigger";
import { Group } from "three";
import { type Flashcard, collection, dealt, finale } from "../../content/cards";
import { one, onResize } from "../dom";
import { type Spring, clamp, damp, lerp, progress, quantize, stepSpring } from "../math";
import type { Picture, Place, Scenery, View } from "../scenery";
import { Stack } from "../stack";
import type { Stage } from "../stage";
import { Cards, type Look, type Placement } from "./cards";
import { Floor } from "./floor";
import type { Light } from "./light";

/**
 * How many cards there are to fly to the deck and stand in it: enough for its depth and for the air at the busiest
 * moment. The last card, after them, ends the stacking.
 */
const POOL = 128;
/**
 * How many cards in front of one hide all of it but the strip along its top: enough that their sides reach past its
 * sides, so only that strip is drawn.
 */
const COVER = 6;
/** How quickly a card eases back a place as one lands in front of it, per second. */
const SETTLE = 9;
/**
 * How much of its shadow a card coming to the deck sheds while it hovers among the others settling onto it, so dozens
 * of shadows don't pile up on the floor; it casts all of it again as it lands.
 */
const CROWD = 0.95;
/** How blurred a card coming to the deck is for each of its own sizes nearer than the deck, in pixels. */
const FOCUS = 9;
/** How much of the collection the count climbs to before it starts blurring into infinity, which it is at the end. */
const BOUNDLESS = 0.62;
/** How blurred the count and infinity are at the far ends of their crossfade, in pixels. */
const HAZE = 16;
/** How far down the screen the section's top is, as a share of the screen, when the stacking starts: its words show. */
const CUE = 0.6;
/** How many of the front card's frames are kept for the cards behind to follow: over a second's worth, at any rate. */
const TRAIL = 256;

/** The deck's cards, as their CSS had them: white, a little cooler toward the foot, over a short soft shadow. */
const LOOK: Look = {
  fill: { angle: 170, from: 1, to: 1, stop: 0.35, tint: [0.965, 0.969, 0.98] },
  edge: 0.06,
  shadows: [{ y: 14, blur: 30, spread: -18, alpha: 0.36 }],
};

/** What a card shows once it's behind another: nothing, as nothing of its face can be seen. */
const BLANK: Flashcard = { deck: "", front: "", back: "" };

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
  torrent: 1,
  ease: 1.42,
  slowest: 0.4,
  slowFlight: 2.4,
  fastFlight: 1.3,
} as const;

/**
 * How the deck answers a mouse and the scroll. The mouse moving draws it along, `follow` card widths for each card
 * width it goes, to at most `reach` card widths away, softly; up `rise` as far as across, as it lifts off the floor,
 * and down only `sink` as far, as the floor's under it. The page scrolling, it lags behind, `trail` card widths for
 * each card width scrolled, to at most `drift` card widths, floor and all. Once both rest, it comes home (`home`, per
 * second). The front card follows on springs a little loose (`stiffness`, `damping`), so it overshoots and settles,
 * leaning as it goes, up to `lean` radians, half that at `speed` card widths a second; each card behind does just the
 * same `lag` seconds after the one in front of it, so a move runs back through the deck like a wave, and the deck
 * comes back square.
 */
const sway = {
  follow: 0.42,
  reach: 0.15,
  rise: 0.55,
  sink: 0.12,
  trail: 0.22,
  drift: 0.09,
  home: 2.4,
  stiffness: 150,
  damping: 13,
  lean: 0.035,
  speed: 1.3,
  lag: 0.012,
} as const;

/**
 * How the deck takes a card landing on it, gently: knocked back into the distance, `push` cards a second, and easing
 * forward again on springs that hardly overshoot (`stiffness`, `damping`), never more than about `most` cards back
 * however many land at once; nudged away from the side the card came from, `knock` card widths a second, and down,
 * `dip`. Each card behind takes it a moment after the one in front of it, as it does the mouse (`sway.lag`).
 */
const landing = {
  push: 9,
  most: 0.9,
  stiffness: 110,
  damping: 18,
  knock: 0.05,
  dip: 0.03,
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

/** Where a card is, from where the deck's front card rests: in CSS pixels, its size against it, turned (radians). */
interface Pose {
  x: number;
  y: number;
  scale: number;
  roll: number;
}

/** A card's way to the deck: when it left, how long it takes, and its path (a curve through `bend`). */
interface Flight {
  readonly start: number;
  readonly duration: number;
  readonly from: Pose;
  readonly bend: { readonly x: number; readonly y: number };
}

/** A card: on its way to the deck (`flight`), in it, or spare; how far back it stands in it, and where it's drawn. */
interface Card {
  readonly face: number;
  flight: Flight | null;
  /** How many cards back it stands in the deck, easing back a place as each lands in front of it. */
  depth: number;
  readonly placement: Placement;
}

/** A card the deck is waiting to send: when, from which corner, how long its flight, and whether it's the last. */
interface Launch {
  readonly at: number;
  readonly side: -1 | 1;
  readonly duration: number;
  readonly last?: true;
}

/**
 * How the deck answers a mouse, the scroll and cards landing on it (`sway`, `landing`): the front card drawn along the
 * way the mouse goes, lagging behind the scroll and knocked back by each card, on springs, and each card behind
 * following it a moment later, as a wave. It keeps what the front card did over the last second, for the cards behind
 * to do the same after it. Nothing in it bends a card: they're moved and leant, square to the eye, and knocked back.
 */
class Wake {
  private readonly x: Spring = { value: 0, velocity: 0 };
  private readonly y: Spring = { value: 0, velocity: 0 };
  /** How far back the cards landing have knocked the deck, in cards. */
  private readonly push: Spring = { value: 0, velocity: 0 };
  /** Where the mouse has drawn the deck to, in card widths, coming home as it rests. */
  private readonly drag = { x: 0, y: 0 };
  /** How far the scroll has left it behind, in card widths, coming home as the scroll rests. */
  private lag = 0;
  /**
   * The front card's last frames, round and round, a sample of five each: when, where across and down, how fast across,
   * and how far knocked back.
   */
  private readonly trail = new Float32Array(TRAIL * 5);
  private head = 0;
  private filled = 0;
  private time = 0;

  /**
   * Moves on by `seconds`, the mouse having gone `dx` across and `dy` down since the last, and the page `scrolled`
   * down, for cards `width` wide.
   */
  follow(dx: number, dy: number, scrolled: number, width: number, seconds: number): void {
    const { drag } = this;
    drag.x = damp(drag.x + (dx / width) * sway.follow, 0, sway.home, seconds);
    drag.y = damp(drag.y + (dy / width) * sway.follow, 0, sway.home, seconds);
    this.lag = damp(this.lag + (scrolled / width) * sway.trail, 0, sway.home, seconds);
    const reach = sway.reach * width;
    const across = reach * Math.tanh(drag.x / sway.reach);
    const drawn = reach * Math.tanh(drag.y / sway.reach) * (drag.y < 0 ? sway.rise : sway.sink);
    const down = drawn + sway.drift * width * Math.tanh(this.lag / sway.drift);
    stepSpring(this.x, across, sway.stiffness, sway.damping, seconds);
    stepSpring(this.y, down, sway.stiffness, sway.damping, seconds);
    stepSpring(this.push, 0, landing.stiffness, landing.damping, seconds);
    this.time += seconds;
    const at = this.head * 5;
    this.trail.set([this.time, this.x.value, this.y.value, this.x.velocity, this.push.value], at);
    this.head = (this.head + 1) % TRAIL;
    this.filled = Math.min(this.filled + 1, TRAIL);
  }

  /** A card has landed on the deck from `side` (-1 the left, 1 the right), cards `width` wide. */
  thud(side: number, width: number): void {
    this.push.velocity += landing.push;
    this.x.velocity -= side * landing.knock * width;
    this.y.velocity += landing.dip * width;
  }

  /**
   * Puts in `pose` where a card `depth` cards back is, from where it rests, following the front card that much later,
   * for cards `width` wide; and how much further back it's knocked, in cards.
   */
  place(pose: Pose, depth: number, width: number): number {
    const { trail } = this;
    const when = this.time - depth * sway.lag;
    let x = 0;
    let y = 0;
    let speed = 0;
    let knocked = 0;
    let newer = -1;
    for (let back = 1; back <= this.filled; back++) {
      const at = ((this.head - back + TRAIL) % TRAIL) * 5;
      const time = trail[at] ?? 0;
      if (time > when && back < this.filled) {
        newer = at;
        continue;
      }
      // Between this frame and the one after it, or the oldest kept.
      const next = newer < 0 ? at : newer;
      const span = (trail[next] ?? 0) - time;
      const t = span > 0 ? clamp((when - time) / span, 0, 1) : 0;
      const mix = (offset: number) => lerp(trail[at + offset] ?? 0, trail[next + offset] ?? 0, t);
      x = mix(1);
      y = mix(2);
      speed = mix(3);
      knocked = mix(4);
      break;
    }
    pose.x = x;
    pose.y = y;
    pose.scale = 1;
    // Leaning back from the way it goes, as if its foot were pulled along and its head came after.
    pose.roll = -sway.lean * Math.tanh(speed / (sway.speed * width));
    return landing.most * Math.tanh(knocked / landing.most);
  }
}

/**
 * The collection section's deck, drawn by the scenery, standing at the foot of the section where the page's still deck
 * stands (whose cards it takes its size and type from), and seen as it is (Stack): cards square one behind the other,
 * the deck's top showing over the front card as a band narrowing away, darker further back. It scrolls with the
 * section, a little behind it as the scenery follows the scroll, fading only as it leaves the screen's top. Once the
 * section's words are on screen, whether or not the deck is yet, cards come to it from beyond the screen's top
 * corners: one, then another, then faster and faster until dozens are in the air at once. Each comes fast, blurred as
 * it passes near, and slows the whole way down, squaring up, so it settles onto the deck's face rather than hitting
 * it, and the deck eases back a card to take it; the count beside climbs with every card, faster than they can be
 * seen, to tens of thousands, blurring into infinity as it nears them. Then a card arrives now and then. A mouse
 * moving draws the whole deck along, the page scrolling leaves it a little behind, and it comes back square (Wake).
 */
export class Pile implements Picture {
  readonly object = new Group();
  /** Resolves once the deck can be shown without holding anything up: its faces drawn, and the GPU ready for it. */
  readonly ready: Promise<void>;
  private readonly stack = new Stack();
  private readonly last: Card;
  /** The cards neither on their way nor in the deck, the next to fly first, so their faces come round in order. */
  private readonly spare: Card[];
  /** The cards on their way, in the order they left. */
  private readonly flying: Card[] = [];
  /** The deck, from its front card back. */
  private readonly deck: Card[] = [];
  private readonly drawn: Cards;
  private readonly floor = new Floor();
  private readonly wake = new Wake();
  /** Where the deck's front card is seen this frame, from where it rests. */
  private readonly front: Pose = { x: 0, y: 0, scale: 1, roll: 0 };
  private readonly blank: number;
  /** The light behind the page, which gathers round the deck as cards land. */
  private readonly light: Light;
  private readonly section: HTMLElement;
  private readonly anchor: HTMLElement;
  private readonly count: HTMLElement;
  private readonly infinity: HTMLElement;
  private readonly queue: Launch[] = [];
  private time = 0;
  /** When each card of the stacking leaves, which the count climbs through. */
  private departures: number[] = [];
  private counted = 0;
  private shownCount = -1;
  /** How far the count has blurred into infinity, from 0 to 1. */
  private boundless = -1;
  /** Where the section is laid out on the page, and the deck in it. */
  private around: Place = { left: 0, top: 0, width: 1, height: 1 };
  private place: Place = { left: 0, top: 0, width: 1, height: 1 };
  /** Where the section's top is on the window, as last drawn, and how much the deck shows. */
  private top = 0;
  private fade = 0;
  private viewport = 1;
  private started = false;
  private stacking = false;
  /** When the next card comes, now and then, once the stacking's done; never before. */
  private idle = Infinity;
  private side: -1 | 1 = 1;
  /** Where the mouse was on the window last frame, while the deck was drawn; null before. */
  private mouse: { x: number; y: number } | null = null;
  /** How far the page was scrolled last frame, while the deck was drawn; null before. */
  private scroll: number | null = null;

  constructor(
    root: HTMLElement,
    scenery: Scenery,
    private readonly stage: Stage,
  ) {
    this.section = root.closest("section") ?? root;
    this.light = scenery.light;
    this.anchor = one(root, "[data-pile-deck]");
    this.count = one(this.section, "[data-pile-count]");
    this.infinity = one(this.section, "[data-pile-infinity]");
    const card = (face: number): Card => ({
      face,
      flight: null,
      depth: 0,
      placement: { x: 0, y: 0, scale: 1, turn: 0, roll: 0, blur: 0, opacity: 0, face },
    });
    this.spare = Array.from({ length: POOL }, (_, index) => card(index % dealt.length));
    this.last = card(dealt.length);
    this.blank = dealt.length + 1;
    this.drawn = new Cards(scenery.renderer, POOL + 1, LOOK, 0);
    this.floor.mesh.renderOrder = 1;
    this.drawn.mesh.renderOrder = 2;
    this.object.add(this.floor.mesh, this.drawn.mesh);

    const sizer = one(root, "[data-pile-sizer]");
    const closing = one(root, "[data-pile-sizer-last]");
    const faces = [
      ...dealt.map((face) => ({ card: face, sizer })),
      { card: finale, sizer: closing },
      { card: BLANK, sizer },
    ];
    const measure = () => {
      this.around = scenery.locate(this.section);
      // The deck's place in the section.
      const deck = scenery.locate(this.anchor);
      this.place = { ...deck, top: deck.top - this.around.top };
      return this.drawn.measure(faces);
    };
    const drawn = measure();
    onResize(this.section, () => void measure());
    ScrollTrigger.addEventListener("refresh", () => void measure());
    scenery.add(this);
    this.ready = drawn.then(() => {
      this.drawn.warm();
      scenery.warm(this);
    });
  }

  update(view: Readonly<View>, seconds: number): boolean {
    // Where the section is, and how much the deck shows: all of it, coming up past the screen's foot, and fading as it
    // crosses the screen's top.
    this.top = this.around.top - view.scroll;
    const deck = this.top + this.place.top;
    this.fade = clamp((deck + this.place.height) / this.place.height, 0, 1);
    // The stacking starts once the section's words are on screen, or the deck wholly is, coming to it from above or
    // below; cards then fly into the section to meet the deck as it comes up.
    if (!this.started && (this.top <= view.height * CUE || deck + this.place.height <= view.height)) {
      this.started = true;
      this.plan();
    }
    if (this.fade <= 0 || this.top >= view.height || (!this.started && deck >= view.height)) {
      this.mouse = null;
      this.scroll = null;
      const was = this.object.visible;
      this.object.visible = false;
      return was;
    }
    this.object.visible = true;
    this.viewport = view.width;
    this.time += seconds;
    if (this.time >= this.idle) {
      this.queue.push({ at: this.time, side: this.side, duration: tempo.slowFlight });
      this.side = this.side === 1 ? -1 : 1;
      this.idle = this.time + 2.2 + Math.random() * 1.6;
    }
    while (this.queue[0] != null && this.queue[0].at <= this.time) {
      const launch = this.queue.shift();
      if (launch != null) this.launch(launch);
    }
    this.feel(view.scroll, seconds);
    this.draw(seconds);
    return true;
  }

  resize(view: Readonly<View>): void {
    this.drawn.setViewport(view.width, view.height);
    this.floor.setViewport(view.width, view.height);
  }

  /**
   * How far the mouse went since the last frame, and the page scrolled, `scroll` now, for the deck to follow: a
   * mouse's, not a finger's, as a finger moves only to scroll.
   */
  private feel(scroll: number, seconds: number): void {
    const { pointer } = this.stage;
    const was = this.mouse;
    this.mouse = pointer.mouse ? { x: pointer.clientX, y: pointer.clientY } : null;
    const step = (value: number) => clamp(value, -120, 120);
    const dx = was && this.mouse ? step(this.mouse.x - was.x) : 0;
    const dy = was && this.mouse ? step(this.mouse.y - was.y) : 0;
    const scrolled = clamp(scroll - (this.scroll ?? scroll), -160, 160);
    this.scroll = scroll;
    this.wake.follow(dx, dy, scrolled, this.place.width, seconds);
  }

  /** Plans the stacking: gaps narrowing to a torrent, then widening again until the last card. */
  private plan(): void {
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
    this.departures = launches.map(({ at }) => at);
  }

  /**
   * How far the count has climbed by `time`. It climbs steadily from each card's leaving to the next's, so it races
   * with the stream rather than behind it: by about one a card while they come one by one, with the rest of the
   * collection on top, growing ever faster with the stream until it's whole as the last card leaves.
   */
  private tally(time: number): number {
    const departures = this.departures;
    const total = departures.length;
    const next = departures.findIndex((at) => at > time);
    const gone = next < 0 ? total : next;
    if (gone === 0) return 0;
    const from = departures[gone - 1] ?? time;
    const to = departures[gone];
    const sent = to == null ? total : gone + (time - from) / (to - from);
    const rush = (collection ** (sent / total) - 1) / (collection - 1);
    return sent + (collection - total) * rush;
  }

  /** Sends a card off from beyond a top corner of the screen, bigger (nearer) and turned a little. */
  private launch({ at, side, duration, last }: Launch): void {
    const card = last ? this.last : this.spare.shift();
    if (card == null) return;
    const { width, height } = this.place;
    const centre = { x: this.place.left + width / 2, y: this.top + this.place.top + height / 2 };
    // From just above the screen's top, a little in from the corner or beyond it, so it comes into view at once.
    const from: Pose = {
      x: (side === -1 ? 0 : this.viewport) - centre.x + side * width * (Math.random() * 0.5 - 0.15),
      y: -centre.y - height * (0.6 + Math.random() * 0.5),
      scale: 1.3 + Math.random() * 0.35,
      roll: (-side * (6 + Math.random() * 12) * Math.PI) / 180,
    };
    // The way bends a little, coming down more steeply at the end.
    card.flight = { start: at, duration, from, bend: { x: from.x * 0.55, y: from.y * 0.8 } };
    this.flying.push(card);
  }

  /**
   * A card has settled onto the deck's face: the deck is knocked back, and eases back a card to take it, its last lost
   * in the distance, and the light behind it gathers round it a little more.
   */
  private land(card: Card, flight: Flight): void {
    card.flight = null;
    const { left, top, width, height } = this.place;
    this.wake.thud(Math.sign(flight.from.x), width);
    this.light.settle(left + width / 2, this.top + top + height / 2, width * 1.6);
    this.flying.splice(this.flying.indexOf(card), 1);
    card.depth = 0;
    this.deck.unshift(card);
    while (this.deck.length > this.stack.depth + 1) {
      const lost = this.deck.pop();
      if (lost != null && lost !== this.last) this.spare.push(lost);
    }
    if (card === this.last) this.done();
  }

  /** The last card has settled: the count is infinity, and from now on a card arrives now and then. */
  private done(): void {
    this.stacking = false;
    this.counted = collection;
    this.idle = this.time + 2.4;
  }

  private draw(seconds: number): void {
    const { left, width, height } = this.place;
    const top = this.top + this.place.top;
    this.see(this.front, 0);
    for (const card of [...this.flying]) this.fly(card, card.flight);
    this.lay(seconds, left + width / 2, top + height / 2);

    if (this.stacking) this.counted = this.tally(this.time);
    const shown = Math.round(this.counted);
    if (shown !== this.shownCount) {
      this.count.textContent = formatter.format(shown);
      this.shownCount = shown;
    }
    this.blur(clamp((this.counted / collection - BOUNDLESS) / (1 - BOUNDLESS), 0, 1));

    // The floor under the front card, deeper as the deck fills, and fainter as the deck lifts off it.
    this.floor.depth = lerp(0.3, 0.55, this.deck.length / this.stack.depth);
    const lifted = clamp(1 + this.front.y / (height * 0.2), 0.5, 1);
    this.floor.place(left + this.front.x, top + Math.max(this.front.y, 0), width, height, this.fade * lifted);
    this.drawn.begin();
    for (let index = this.deck.length - 1; index >= 0; index--) {
      const card = this.deck[index];
      if (card != null) this.drawn.add(card.placement);
    }
    for (const card of this.flying) this.drawn.add(card.placement);
    this.drawn.end();
  }

  /**
   * Places the deck's cards, its front card resting with its middle at (`x`, `y`) on the window. Each card is seen as
   * far back as it stands (Stack): smaller, toward where the deck's edges run to, and darker; and as far as the mouse
   * has drawn it, which it's drawn in its own plane, so the further back, the less it goes. Once a card is wholly
   * behind another, its face is blank, and once its sides are behind the cards in front, only its top is drawn.
   */
  private lay(seconds: number, x: number, y: number): void {
    const { stack, deck } = this;
    const pose: Pose = { x: 0, y: 0, scale: 1, roll: 0 };
    deck.forEach((card, index) => {
      card.depth = damp(card.depth, index, SETTLE, seconds);
      const { depth, placement } = card;
      this.see(pose, depth);
      placement.x = x + pose.x;
      placement.y = y + pose.y;
      placement.scale = pose.scale;
      placement.roll = pose.roll;
      placement.blur = 0;
      placement.shade = stack.shade(depth);
      placement.lift = clamp(1 - depth, 0, 1);
      placement.opacity = stack.presence(depth) * this.fade;
      placement.face = depth > 1.5 ? this.blank : card.face;
      const cover = deck[index - COVER]?.placement;
      placement.cut =
        cover != null && cover.opacity >= 1 && placement.lift === 0 ? this.drawn.cut(placement, cover) : undefined;
    });
  }

  /**
   * Puts in `pose` where a card `depth` cards back is seen, from where the front card rests: as far as the mouse, the
   * scroll and the cards landing have taken it (Wake), all in its own plane, and then as far back as it's knocked
   * besides, smaller and higher toward where the deck's edges run to.
   */
  private see(pose: Pose, depth: number): void {
    const { width, height } = this.place;
    const knocked = this.wake.place(pose, depth, width);
    const scale = this.stack.scale(depth + knocked);
    const rise = this.stack.horizon * height;
    pose.x *= scale;
    pose.y = (pose.y + rise) * scale - rise;
    pose.scale = scale;
  }

  /**
   * A card on its way: along its curve to the deck's front card quickly, then nearer and nearer its face, shrinking to
   * the deck's size and coming into focus as it settles onto it, its shadow fading among the others there (CROWD). It's
   * unseen above the section and fades in as it comes into it, so it never shows over the section before, mid-flight
   * or waiting for the section to come back.
   */
  private fly(card: Card, flight: Flight | null): void {
    if (flight == null) return;
    const t = clamp((this.time - flight.start) / flight.duration, 0, 1);
    if (t >= 1) {
      this.land(card, flight);
      return;
    }
    const { width, height } = this.place;
    const u = arrive(t);
    const { from, bend } = flight;
    const { front } = this;
    // Along the curve from `from` through `bend` to the front card.
    const along = (a: number, b: number, c: number) => (1 - u) ** 2 * a + 2 * u * (1 - u) * b + u * u * c;
    const x = along(from.x, bend.x, front.x);
    const y = along(from.y, bend.y, front.y);
    const scale = lerp(from.scale, front.scale, press(t));
    const rise = this.place.top + height / 2;
    Object.assign(card.placement, {
      x: this.place.left + width / 2 + x,
      y: this.top + rise + y,
      scale,
      roll: lerp(from.roll, front.roll, straighten(t)),
      blur: (scale - 1) * FOCUS,
      shade: 0,
      lift: 1 - CROWD * progress(t, 0.3, 0.55) * (1 - progress(t, 0.9, 1)),
      cut: undefined,
      face: card.face,
      opacity: Math.min(1, t / 0.05, clamp((rise + y) / (height * 1.5), 0, 1)) * this.fade,
    });
  }

  /** The count blurring away as infinity comes sharp in its place, `t` of the way from one to the other. */
  private blur(t: number): void {
    const boundless = quantize(t * t * (3 - 2 * t), 0.004);
    if (boundless === this.boundless) return;
    this.boundless = boundless;
    const count = this.count.style;
    count.opacity = String(1 - boundless);
    count.filter = boundless > 0 ? `blur(${(HAZE * boundless).toFixed(2)}px)` : "";
    const infinity = this.infinity.style;
    infinity.opacity = String(boundless);
    infinity.filter = boundless < 1 ? `blur(${(HAZE * (1 - boundless)).toFixed(2)}px)` : "";
    infinity.transform = `scale(${lerp(0.86, 1, boundless).toFixed(4)})`;
  }
}
