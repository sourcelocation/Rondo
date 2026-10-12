import { mdiAirplane } from "@mdi/js";
import { CanvasTexture, SRGBColorSpace } from "three";
import logo from "../../../../../brand/dist/svg/logo.svg?raw";
import { crops } from "../../brand";
import { studying } from "../../content/cards";

/**
 * Rondo on the laptop's screen, studying: a sidebar with the logo, today's session and the decks, and offline at its
 * foot; beside it, today's cards one at a time. Each slides in showing its front; "Show answer" is pressed and the
 * answer comes in under a hairline; "Good" is pressed, and the card goes, one fewer due. The screen shows only its
 * picture's lightness (laptop.ts), so it's drawn in greys, in the page's own type.
 */

/** One card's turn, in seconds, and the moments in it. */
const TURN = {
  length: 3.6,
  /** The card slides in. */
  enter: [0, 0.45],
  /** "Show answer" is pressed, and the answer comes in. */
  press: [1.5, 1.62],
  reveal: [1.62, 1.85],
  /** "Good" is pressed, and the card goes. */
  grade: [2.9, 3.05],
  leave: [3.1, 3.45],
} as const;
/** The stretches of a turn when nothing on the screen moves, so it isn't drawn again. */
const STILL: readonly (readonly [number, number])[] = [
  [TURN.enter[1], TURN.press[0]],
  [TURN.reveal[1], TURN.grade[0]],
  [TURN.leave[1], TURN.length],
];
/** How many cards are due when studying starts. */
const DUE = 24;
/** The rating pressed. */
const GOOD = 2;

const INK = "#0b0b0d";
const INK_2 = "#4a4b52";
const INK_3 = "#66676e";
const SANS = "Geist, system-ui, sans-serif";
const SERIF = "Juana, Georgia, serif";

export class Study {
  readonly texture: CanvasTexture;
  private readonly canvas = document.createElement("canvas");
  private readonly context: CanvasRenderingContext2D | null;
  /** A hundredth of the screen's width, which everything on it is measured in. */
  private readonly unit: number;
  /** The decks in the sidebar. */
  private readonly decks: string[];
  /** Which still stretch was drawn last, so it's drawn once (-1 while moving). */
  private drawn = -2;

  /** A screen `width` pixels across, `aspect` as tall as it is wide. */
  constructor(width: number, aspect: number, anisotropy: number) {
    this.canvas.width = width;
    this.canvas.height = Math.round(width * aspect);
    this.context = this.canvas.getContext("2d");
    this.unit = width / 100;
    this.decks = [...new Set(studying.cards.map((card) => card.deck))].slice(0, 6);
    this.texture = new CanvasTexture(this.canvas);
    this.texture.colorSpace = SRGBColorSpace;
    this.texture.anisotropy = anisotropy;
    this.at(0);
  }

  /**
   * The screen `time` seconds into studying. Whether its picture changed: in a still stretch it's drawn once and
   * left, so the film needn't draw a frame for it.
   */
  at(time: number): boolean {
    const turn = Math.floor(time / TURN.length);
    const t = time - turn * TURN.length;
    const still = STILL.findIndex(([from, to]) => t >= from && t < to);
    const key = still < 0 ? -1 : turn * STILL.length + still;
    if (key >= 0 && key === this.drawn) return false;
    this.drawn = key;
    this.draw(turn, t);
    this.texture.needsUpdate = true;
    return true;
  }

  private draw(turn: number, t: number): void {
    const { context: c, unit: u, canvas } = this;
    if (c == null) return;
    const answered = turn + (t >= TURN.leave[0] ? 1 : 0);
    c.clearRect(0, 0, canvas.width, canvas.height);
    c.fillStyle = "#ffffff";
    c.fillRect(0, 0, canvas.width, canvas.height);
    this.sidebar(c, u, Math.max(DUE - answered, 0));

    // Today's session, and how many are left.
    c.textBaseline = "alphabetic";
    c.textAlign = "left";
    c.fillStyle = INK;
    c.font = `500 ${2.4 * u}px ${SANS}`;
    c.fillText(studying.session, 27 * u, 9 * u);
    const titled = c.measureText(studying.session).width;
    c.fillStyle = INK_3;
    c.font = `400 ${1.6 * u}px ${SANS}`;
    c.fillText(`${Math.max(DUE - answered, 0)} ${studying.due}`, 27 * u + titled + 1.4 * u, 9 * u);

    // The card: in, answered, out.
    const card = studying.cards[turn % studying.cards.length];
    if (card == null) return;
    const entering = this.ease(this.share(t, TURN.enter));
    const leaving = this.ease(this.share(t, TURN.leave));
    const shift = (1 - entering) * 4 * u - leaving * 4 * u;
    const shown = entering * (1 - leaving);
    const answer = this.ease(this.share(t, TURN.reveal)) * (1 - leaving);
    const box = { x: 38 * u + shift, y: 15 * u, width: 46 * u, height: 34 * u };

    c.save();
    c.globalAlpha = shown;
    c.shadowColor = "rgb(30 40 80 / 16%)";
    c.shadowBlur = 3 * u;
    c.shadowOffsetY = 1.2 * u;
    this.rounded(c, box.x, box.y, box.width, box.height, 1.6 * u);
    c.fillStyle = "#ffffff";
    c.fill();
    c.shadowColor = "transparent";
    c.lineWidth = Math.max(1, 0.1 * u);
    c.strokeStyle = "rgb(11 11 13 / 9%)";
    c.stroke();

    const middle = box.x + box.width / 2;
    c.fillStyle = INK_3;
    c.font = `450 ${1.3 * u}px ${SANS}`;
    c.fillText(card.deck, box.x + 2.4 * u, box.y + 3.6 * u);
    c.textAlign = "center";
    c.fillStyle = INK;
    c.font = `420 ${4.4 * u}px ${SANS}`;
    c.fillText(card.front, middle, box.y + 14 * u, box.width - 6 * u);

    c.globalAlpha = shown * answer;
    c.fillStyle = "rgb(11 11 13 / 14%)";
    c.fillRect(box.x + 4 * u, box.y + 19 * u, box.width - 8 * u, Math.max(1, 0.1 * u));
    c.fillStyle = INK_2;
    c.font = `300 ${3 * u}px ${SERIF}`;
    c.fillText(card.back, middle, box.y + 25.5 * u, box.width - 6 * u);
    c.restore();

    // Below it, "Show answer", then the ratings, each pressed in turn.
    const row = { x: 38 * u, y: 53 * u, width: 46 * u, height: 4.6 * u };
    const rates = this.ease(this.share(t, TURN.reveal)) * (1 - this.ease(this.share(t, TURN.leave)));
    const pressing = this.pulse(this.share(t, TURN.press));
    c.save();
    c.globalAlpha = 1 - rates;
    const inset = pressing * 0.4 * u;
    this.rounded(c, row.x + inset, row.y + inset, row.width - 2 * inset, row.height - 2 * inset, row.height / 2);
    c.fillStyle = pressing > 0 ? "#3a3b40" : INK;
    c.fill();
    c.fillStyle = "#ffffff";
    c.font = `500 ${1.6 * u}px ${SANS}`;
    c.textAlign = "center";
    c.textBaseline = "middle";
    c.fillText(studying.show, row.x + row.width / 2, row.y + row.height / 2);

    c.globalAlpha = rates;
    const gap = 1 * u;
    const each = (row.width - 3 * gap) / 4;
    const graded = t >= TURN.grade[0];
    studying.ratings.forEach((rating, index) => {
      const x = row.x + index * (each + gap);
      const chosen = index === GOOD && graded;
      this.rounded(c, x, row.y, each, row.height, row.height / 2);
      c.fillStyle = chosen ? INK : "#f1f3f6";
      c.fill();
      if (!chosen) {
        c.lineWidth = Math.max(1, 0.08 * u);
        c.strokeStyle = "rgb(11 11 13 / 7%)";
        c.stroke();
      }
      c.fillStyle = chosen ? "#ffffff" : INK_2;
      c.fillText(rating, x + each / 2, row.y + row.height / 2);
    });
    c.restore();
  }

  /** The sidebar: the logo, today's session with what's left, the decks, and at its foot, offline. */
  private sidebar(c: CanvasRenderingContext2D, u: number, due: number): void {
    const { height } = this.canvas;
    c.fillStyle = "#f5f6f8";
    c.fillRect(0, 0, 22 * u, height);
    c.fillStyle = "rgb(11 11 13 / 7%)";
    c.fillRect(22 * u - Math.max(1, 0.08 * u), 0, Math.max(1, 0.08 * u), height);
    this.logo(c, 3 * u, 6 * u, 2.2 * u);

    c.textBaseline = "middle";
    c.textAlign = "left";
    const line = 4.2 * u;
    const top = 15 * u;
    this.rounded(c, 1.6 * u, top - line / 2 + 0.3 * u, 18.8 * u, line - 0.6 * u, 1 * u);
    c.fillStyle = "#e7e8ec";
    c.fill();
    c.fillStyle = INK;
    c.font = `500 ${1.55 * u}px ${SANS}`;
    c.fillText(studying.session, 3 * u, top);
    c.textAlign = "right";
    c.fillStyle = INK_3;
    c.font = `450 ${1.35 * u}px ${SANS}`;
    c.fillText(String(due), 19 * u, top);
    c.textAlign = "left";
    c.fillStyle = INK_2;
    c.font = `450 ${1.55 * u}px ${SANS}`;
    this.decks.forEach((deck, index) => c.fillText(deck, 3 * u, top + (index + 1.4) * line));

    // Offline: the airplane-mode glyph (its own box runs from about 3.3 to 21.2 of its 24) and the word.
    const glyph = 1.7 * u;
    const foot = height - 4.5 * u;
    c.save();
    c.translate(3 * u + glyph / 2, foot);
    c.scale(glyph / 17.9, glyph / 17.9);
    c.translate(-12.25, -12);
    c.fillStyle = INK_3;
    c.fill(new Path2D(mdiAirplane));
    c.restore();
    c.fillStyle = INK_3;
    c.font = `450 ${1.35 * u}px ${SANS}`;
    c.fillText(studying.offline, 3 * u + glyph + 0.9 * u, foot);
  }

  /** Rondo's logo, `size` tall, its top left corner at (x, y). */
  private logo(c: CanvasRenderingContext2D, x: number, y: number, size: number): void {
    const [left = 0, top = 0, , height = 1] = crops.logo.split(" ").map(Number);
    const scale = size / height;
    c.save();
    c.fillStyle = INK;
    c.translate(x, y);
    c.scale(scale, scale);
    c.translate(-left, -top);
    // The wordmark's letters, then the mark, which the artwork moves into place in a group of its own.
    const [letters = "", mark = ""] = logo.split(/<g[^>]*transform=/u);
    for (const [, path] of letters.matchAll(/<path d="([^"]+)"/gu)) if (path != null) c.fill(new Path2D(path));
    const shift = /translate\(([-\d.]+),\s*([-\d.]+)\)/u.exec(mark);
    c.translate(Number(shift?.[1] ?? 0), Number(shift?.[2] ?? 0));
    for (const [, path] of mark.matchAll(/<path d="([^"]+)"/gu)) {
      if (path != null) c.fill(new Path2D(path), "evenodd");
    }
    c.restore();
  }

  private rounded(c: CanvasRenderingContext2D, x: number, y: number, width: number, height: number, r: number): void {
    c.beginPath();
    c.roundRect(x, y, width, height, r);
  }

  /** How far `t` is through a moment, 0 to 1. */
  private share(t: number, [from, to]: readonly [number, number]): number {
    return Math.min(Math.max((t - from) / (to - from), 0), 1);
  }

  private ease(x: number): number {
    return 1 - (1 - x) ** 3;
  }

  /** A press: down and back up over the moment, nothing outside it. */
  private pulse(x: number): number {
    return x > 0 && x < 1 ? Math.sin(x * Math.PI) : 0;
  }
}
