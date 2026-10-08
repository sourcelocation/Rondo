import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { all, one } from "./dom";

const SVG = "http://www.w3.org/2000/svg";

/** Cards due on each device at the start of the day. */
const DUE = 24;

interface Point {
  readonly x: number;
  readonly y: number;
}

interface Box {
  readonly x: number;
  readonly y: number;
  readonly w: number;
  readonly h: number;
}

type Curve = readonly [Point, Point, Point, Point];

const mid = (a: Point, b: Point): Point => ({ x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 });
const centre = (box: Box): Point => ({ x: box.x + box.w / 2, y: box.y + box.h / 2 });
const svgPath = ([a, b, c, d]: Curve) => `M ${a.x} ${a.y} C ${b.x} ${b.y}, ${c.x} ${c.y}, ${d.x} ${d.y}`;

/** Splits a cubic curve at its middle into the half from its start and the half from its end, both ending there. */
function halves([p0, p1, p2, p3]: Curve): [Curve, Curve] {
  const p01 = mid(p0, p1);
  const p12 = mid(p1, p2);
  const p23 = mid(p2, p3);
  const p012 = mid(p01, p12);
  const p123 = mid(p12, p23);
  const m = mid(p012, p123);
  return [
    [p0, p01, p012, m],
    [p3, p23, p123, m],
  ];
}

/** One of the two devices: its screen, its count of cards due, the card it shows, and its link to the account. */
class Device {
  readonly screen: HTMLElement;
  private readonly card: HTMLElement;
  private readonly word: HTMLElement;
  private readonly due: HTMLElement;
  private readonly queue: string[];
  readonly near: SVGPathElement;
  readonly far: SVGPathElement;
  readonly route: SVGPathElement;
  readonly spark: SVGCircleElement;
  /** How many answers the device gives in the story. */
  total = 0;
  private dueNow = DUE;
  private shown = 0;

  constructor(
    readonly element: HTMLElement,
    links: SVGSVGElement,
    side: string,
  ) {
    this.screen = one(element, ".device__screen");
    this.card = one(element, "[data-card]");
    this.word = one(element, "[data-word]");
    this.due = one(element, "[data-due]");
    this.queue = (this.word.dataset.queue ?? "").split(" ");
    this.near = one(links, `[data-half="${side}-near"]`, SVGPathElement);
    this.far = one(links, `[data-half="${side}-far"]`, SVGPathElement);
    this.route = one(links, `[data-route="${side}"]`, SVGPathElement);
    this.spark = one(links, `[data-spark="${side}"]`, SVGCircleElement);
  }

  /** Back to the start of a day, at once. */
  clear(): void {
    this.dueNow = DUE;
    this.shown = 0;
    this.due.textContent = String(DUE);
    this.word.textContent = this.queue[0] ?? "";
    this.element.dataset.online = "true";
    this.element.classList.remove("is-updated");
  }

  online(timeline: gsap.core.Timeline, online: boolean, at: number): void {
    timeline.call(() => (this.element.dataset.online = String(online)), undefined, at);
  }

  /** The card on screen is answered: it turns over to the next one, and one fewer is due. */
  answer(timeline: gsap.core.Timeline, at: number): void {
    this.shown = (this.shown + 1) % this.queue.length;
    this.flip(timeline, this.queue[this.shown] ?? "", at);
    this.count(timeline, this.dueNow - 1, at + 0.1);
  }

  /** Shows the first card again: a new day. */
  restart(timeline: gsap.core.Timeline, at: number): void {
    this.shown = 0;
    this.flip(timeline, this.queue[0] ?? "", at);
  }

  private flip(timeline: gsap.core.Timeline, word: string, at: number): void {
    timeline
      .to(this.card, { scale: 0.95, duration: 0.12, ease: "power2.out" }, at)
      .to(this.card, { scale: 1, duration: 0.9, ease: "elastic.out(1, 0.5)" }, at + 0.12)
      .to(this.word, { rotationX: -90, opacity: 0, filter: "blur(4px)", duration: 0.22, ease: "power2.in" }, at)
      .call(() => (this.word.textContent = word), undefined, at + 0.22)
      .fromTo(
        this.word,
        { rotationX: 90, opacity: 0, filter: "blur(4px)" },
        { rotationX: 0, opacity: 1, filter: "blur(0px)", duration: 0.55, ease: "expo.out" },
        at + 0.22,
      );
  }

  /** Rolls the count of cards due to `value`. */
  count(timeline: gsap.core.Timeline, value: number, at: number): void {
    const up = value > this.dueNow;
    this.dueNow = value;
    timeline
      .to(this.due, { yPercent: up ? 45 : -45, opacity: 0, filter: "blur(4px)", duration: 0.2, ease: "power2.in" }, at)
      .call(() => (this.due.textContent = String(value)), undefined, at + 0.2)
      .fromTo(
        this.due,
        { yPercent: up ? -45 : 45, opacity: 0, filter: "blur(4px)" },
        { yPercent: 0, opacity: 1, filter: "blur(0px)", duration: 0.6, ease: "expo.out" },
        at + 0.2,
      );
  }
}

interface Answer {
  readonly element: HTMLElement;
  readonly device: Device;
  /** Its place on its device's row. */
  readonly slot: number;
}

/**
 * Sync with both devices offline, told on a loop while it's on screen. The phone and the laptop lose the network
 * (their links to your account come apart) and keep studying: each answer drops out of its device onto that
 * device's own row. Back online, the links join again and the two rows merge into one in the order the answers
 * happened; then both devices take everything, each counting all five answers. Then a new day begins.
 */
export class SyncScene {
  private readonly phone: Device;
  private readonly laptop: Device;
  private readonly links: SVGSVGElement;
  private readonly hub: HTMLElement;
  private readonly ring: HTMLElement;
  private readonly caption: HTMLElement;
  private readonly spots: Record<"phone" | "laptop" | "account", HTMLElement>;
  private readonly answers: Answer[];
  private timeline: gsap.core.Timeline | null = null;
  private showing = false;

  constructor(
    private readonly root: HTMLElement,
    private readonly still: boolean,
  ) {
    this.links = one(root, "svg.merge__links", SVGSVGElement);
    this.phone = new Device(one(root, '[data-device="phone"]'), this.links, "phone");
    this.laptop = new Device(one(root, '[data-device="laptop"]'), this.links, "laptop");
    this.hub = one(root, "[data-hub]");
    this.ring = one(root, "[data-hub-ring]");
    this.caption = one(root, "[data-caption]");
    this.spots = {
      phone: one(root, '[data-spot="phone"]'),
      laptop: one(root, '[data-spot="laptop"]'),
      account: one(root, '[data-spot="account"]'),
    };
    this.answers = all(root, "[data-answer]").map((element) => {
      const device = element.dataset.answer === "laptop" ? this.laptop : this.phone;
      return { element, device, slot: device.total++ };
    });

    const relayout = () => {
      this.link();
      if (this.still) this.settle();
    };
    window.addEventListener("resize", relayout);
    void document.fonts.ready.then(relayout);
    relayout();
    if (still) return;

    ScrollTrigger.create({
      trigger: root,
      start: "top 70%",
      end: "bottom 15%",
      onToggle: ({ isActive }) => {
        this.showing = isActive;
        if (isActive) this.play();
        else this.timeline?.pause();
      },
    });
  }

  private play(): void {
    if (this.timeline != null) {
      this.timeline.play();
      return;
    }
    this.timeline = this.story().eventCallback("onComplete", () => {
      this.timeline = null;
      if (this.showing) this.play();
    });
  }

  /** The story's last frame, for reduced motion: everything merged, both devices counting it all. */
  private settle(): void {
    this.answers.forEach(({ element }, index) => {
      gsap.set(element, { ...this.fanSlot(index, element), opacity: 1 });
    });
    const timeline = gsap.timeline({ defaults: { immediateRender: false } });
    for (const device of [this.phone, this.laptop]) device.count(timeline, DUE - this.answers.length, 0);
    timeline.progress(1);
    this.caption.textContent = "Every answer counts, on both devices.";
  }

  private story(): gsap.core.Timeline {
    this.link();
    // Tweens set their starting state when they start, not when the story is put together.
    const timeline = gsap.timeline({ defaults: { immediateRender: false } });
    const devices = [this.phone, this.laptop];
    const halves = devices.flatMap((device) => [device.near, device.far]);
    const elements = this.answers.map((answer) => answer.element);
    for (const device of devices) device.clear();
    for (const { element, device } of this.answers) {
      gsap.set(element, { ...this.origin(device, element), rotate: 0, scale: 1, opacity: 0, filter: "blur(0px)" });
    }
    gsap.set(halves, { strokeDashoffset: 0 });
    gsap.set(this.hub, { opacity: 1, scale: 1 });
    this.caption.textContent = "Both devices are in sync.";

    // The network goes: the links come apart from the middle, and the account fades.
    this.say(timeline, "Both go offline. Studying goes on.", 0.9);
    devices.forEach((device, index) => device.online(timeline, false, 0.9 + index * 0.15));
    timeline.to(
      halves,
      {
        // A little past the length, so no round end is left behind as a dot.
        strokeDashoffset: (_: number, path: SVGPathElement) => path.getTotalLength() + 2,
        duration: 0.9,
        ease: "power2.inOut",
      },
      0.95,
    );
    timeline.to(this.hub, { opacity: 0.45, scale: 0.94, duration: 0.9, ease: "power2.inOut" }, 0.95);

    // Studying offline: each answer drops out of its device onto the device's own row.
    let t = 2.1;
    for (const answer of this.answers) {
      this.answer(timeline, answer, t);
      t += 0.95;
    }

    // The network comes back: the links reach for each other and join, and the account lights up.
    const back = t + 0.5;
    this.say(timeline, "Back online.", back);
    devices.forEach((device, index) => device.online(timeline, true, back + index * 0.12));
    timeline.to(halves, { strokeDashoffset: 0, duration: 0.9, ease: "expo.inOut" }, back + 0.05);
    for (const device of devices) {
      timeline.fromTo(
        device.spark,
        { attr: { r: 2 }, opacity: 1 },
        { attr: { r: 14 }, opacity: 0, duration: 0.9, ease: "expo.out" },
        back + 0.8,
      );
    }
    timeline.to(this.hub, { opacity: 1, scale: 1, duration: 0.8, ease: "back.out(1.8)" }, back + 0.75);
    timeline.fromTo(
      this.ring,
      { scale: 1, opacity: 0.9 },
      { scale: 1.7, opacity: 0, duration: 1.3, ease: "expo.out" },
      back + 0.8,
    );

    // The two rows become one, in the order the answers happened.
    const merge = back + 1.5;
    this.say(timeline, "The answers meet, in the order they happened.", merge);
    this.answers.forEach((answer, index) => {
      this.merge(timeline, answer, index, merge + 0.3 + index * 0.3);
    });

    // The account sends everything to both devices: each now counts all five answers.
    const down = merge + 0.3 + this.answers.length * 0.3 + 0.8;
    for (const device of devices) {
      this.pulse(timeline, device.route, down);
      device.count(timeline, DUE - this.answers.length, down + 0.85);
      timeline.call(() => device.element.classList.add("is-updated"), undefined, down + 0.85);
      timeline.call(() => device.element.classList.remove("is-updated"), undefined, down + 2.6);
    }
    this.say(timeline, "Every answer counts, on both devices.", down + 0.9);

    // A new day: the answers go, the counts come back, and it all starts over.
    const day = down + 5;
    this.say(timeline, "A new day.", day);
    timeline.to(
      elements,
      { opacity: 0, scale: 0.9, filter: "blur(8px)", duration: 0.7, ease: "power2.in", stagger: 0.05 },
      day,
    );
    for (const device of devices) {
      device.count(timeline, DUE, day + 0.4);
      device.restart(timeline, day + 0.5);
    }
    timeline.to({}, { duration: 2 }, day + 0.6);
    return timeline;
  }

  /** An answer on a device: the screen turns to the next card, and the answer slides out below onto the row. */
  private answer(timeline: gsap.core.Timeline, { element, device, slot }: Answer, at: number): void {
    device.answer(timeline, at);
    const origin = this.origin(device, element);
    const target = this.rowSlot(device, slot, element);
    timeline
      .set(element, { ...origin, rotate: 0, scale: 0.8, opacity: 0, zIndex: 1 }, at)
      .to(element, { y: origin.y + 34, scale: 1, opacity: 1, duration: 0.4, ease: "power2.out" }, at + 0.05)
      .to(element, { x: target.x, duration: 0.75, ease: "power2.inOut" }, at + 0.4)
      .to(element, { y: target.y, rotate: target.rotate, duration: 0.75, ease: "back.out(1.6)" }, at + 0.4);
  }

  /** An answer leaves its device's row in an arc and takes its place in the account's, by when it happened. */
  private merge(timeline: gsap.core.Timeline, { element, device, slot }: Answer, index: number, at: number): void {
    const from = this.rowSlot(device, slot, element);
    const target = this.fanSlot(index, element);
    const lift = Math.min(target.y, from.y) - 70;
    timeline
      .set(element, { zIndex: 3 + index }, at)
      .to(element, { x: target.x, rotate: target.rotate, duration: 1, ease: "power2.inOut" }, at)
      .to(element, { y: lift, duration: 0.5, ease: "power2.out" }, at)
      .to(element, { y: target.y, duration: 0.5, ease: "power2.in" }, at + 0.5)
      .to(element, { scale: 1.08, duration: 0.5, ease: "sine.inOut", yoyo: true, repeat: 1 }, at);
  }

  /** A light running along a link from the account to the device. */
  private pulse(timeline: gsap.core.Timeline, route: SVGPathElement, at: number): void {
    const glow = document.createElementNS(SVG, "circle");
    const dot = document.createElementNS(SVG, "circle");
    glow.setAttribute("class", "pulse-glow");
    glow.setAttribute("r", "10");
    dot.setAttribute("class", "pulse");
    dot.setAttribute("r", "3.5");
    const progress = { t: 0 };
    timeline.call(() => this.links.append(glow, dot), undefined, at);
    timeline.fromTo(
      progress,
      { t: 0 },
      {
        t: 1,
        duration: 0.85,
        ease: "power2.inOut",
        onUpdate: () => {
          // The route runs from the device to the account; the light goes the other way.
          const point = route.getPointAtLength((1 - progress.t) * route.getTotalLength());
          for (const circle of [glow, dot]) {
            circle.setAttribute("cx", point.x.toFixed(2));
            circle.setAttribute("cy", point.y.toFixed(2));
          }
        },
      },
      at,
    );
    timeline.call(
      () => {
        glow.remove();
        dot.remove();
      },
      undefined,
      at + 0.85,
    );
  }

  /** Changes the line under the stage, out of one blur and into another. */
  private say(timeline: gsap.core.Timeline, text: string, at: number): void {
    timeline
      .to(this.caption, { opacity: 0, y: -6, filter: "blur(6px)", duration: 0.3, ease: "power2.in" }, at)
      .call(() => (this.caption.textContent = text), undefined, at + 0.3)
      .fromTo(
        this.caption,
        { opacity: 0, y: 6, filter: "blur(6px)" },
        { opacity: 1, y: 0, filter: "blur(0px)", duration: 0.6, ease: "expo.out" },
        at + 0.3,
      );
  }

  private box(element: Element): Box {
    const stage = this.root.getBoundingClientRect();
    const rect = element.getBoundingClientRect();
    return { x: rect.left - stage.left, y: rect.top - stage.top, w: rect.width, h: rect.height };
  }

  /** Where an answer starts: hidden behind the foot of its device's screen. */
  private origin(device: Device, element: HTMLElement): Point {
    const screen = this.box(device.screen);
    return { x: screen.x + screen.w / 2 - element.offsetWidth / 2, y: screen.y + screen.h - element.offsetHeight - 6 };
  }

  /** The `slot`th place on a device's row, under the device. */
  private rowSlot(device: Device, slot: number, element: HTMLElement): Point & { rotate: number } {
    const spot = centre(this.box(device === this.phone ? this.spots.phone : this.spots.laptop));
    const offset = slot - (device.total - 1) / 2;
    return {
      x: spot.x + offset * element.offsetWidth * 0.72 - element.offsetWidth / 2,
      y: spot.y - element.offsetHeight / 2,
      rotate: offset * 4,
    };
  }

  /** The `index`th place in the account's row: all the answers, in order, fanned. */
  private fanSlot(index: number, element: HTMLElement): Point & { rotate: number } {
    const spot = centre(this.box(this.spots.account));
    const offset = index - (this.answers.length - 1) / 2;
    return {
      x: spot.x + offset * element.offsetWidth * 0.76 - element.offsetWidth / 2,
      y: spot.y + Math.abs(offset) * 4 - element.offsetHeight / 2,
      rotate: offset * 4,
    };
  }

  /** Draws each device's link to the account: across the stage, or up to it on narrow screens. */
  private link(): void {
    const hub = this.box(this.hub);
    const account = centre(hub);
    for (const device of [this.phone, this.laptop]) {
      const screen = this.box(device.screen);
      const at = centre(screen);
      let curve: Curve;
      if (Math.abs(at.y - account.y) < screen.h / 2) {
        const start = { x: at.x < account.x ? screen.x + screen.w + 14 : screen.x - 14, y: at.y };
        const end = { x: at.x < account.x ? hub.x - 10 : hub.x + hub.w + 10, y: account.y };
        const bend = (end.x - start.x) / 2;
        curve = [start, { x: start.x + bend, y: start.y }, { x: end.x - bend, y: end.y }, end];
      } else {
        const start = { x: at.x, y: screen.y - 14 };
        const end = { x: account.x + (at.x < account.x ? -hub.w / 4 : hub.w / 4), y: hub.y + hub.h + 4 };
        const bend = (end.y - start.y) / 2;
        curve = [start, { x: start.x, y: start.y + bend }, { x: end.x, y: end.y - bend }, end];
      }
      device.route.setAttribute("d", svgPath(curve));
      const [near, far] = halves(curve);
      const parts: [SVGPathElement, Curve][] = [
        [device.near, near],
        [device.far, far],
      ];
      for (const [path, half] of parts) {
        path.setAttribute("d", svgPath(half));
        const length = path.getTotalLength();
        path.style.strokeDasharray = `${length} ${length}`;
      }
      device.spark.setAttribute("cx", String(near[3].x));
      device.spark.setAttribute("cy", String(near[3].y));
    }
  }
}
