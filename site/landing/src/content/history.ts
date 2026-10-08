/**
 * One card's memory, as the Anki section draws it. Recall falls between reviews and comes back to the top at each;
 * every review comes when it has dipped to 90% (what FSRS aims for), so the gaps between them keep growing. The card
 * came over from Anki partway through and goes on in Rondo as if nothing happened. Coordinates are the chart's.
 */
export const chart = {
  width: 600,
  height: 300,
  /** 100% recall. */
  top: 72,
  /** 90% recall: when reviews come. */
  target: 196,
  /** The foot of the shaded area. */
  base: 236,
} as const;

/** Where the card was first learned. */
const learned = 40;

/** The gaps between reviews, as drawn and as said. */
const gaps = [
  { width: 30, label: "1d" },
  { width: 44, label: "3d" },
  { width: 62, label: "8d" },
  { width: 86, label: "3w" },
  { width: 120, label: "2mo" },
  { width: 168, label: "5mo" },
] as const;

/** A review: where it happened, and the gap before it with its label in the middle. */
export interface Review {
  readonly x: number;
  readonly from: number;
  readonly label: string;
  readonly labelX: number;
}

export const reviews: readonly Review[] = gaps.reduce<Review[]>((all, gap) => {
  const from = all.at(-1)?.x ?? learned;
  const x = from + gap.width;
  return [...all, { x, from, label: gap.label, labelX: (from + x) / 2 }];
}, []);

/** The point `fraction` of the way through the gap before review `index`. */
function along(index: number, fraction: number): number {
  const review = reviews[index];
  if (review == null) throw new Error(`The card has no review ${index}`);
  return Math.round(review.from + (review.x - review.from) * fraction);
}

/**
 * Where the curve starts; where the card was imported, a quarter into the gap after its fourth review; now, a third
 * into the gap after the first review in Rondo; and the next review, still to come.
 */
export const marks = {
  learned,
  seam: along(4, 0.24),
  now: along(5, 0.36),
  next: along(5, 1),
} as const;

/** The curve's height at `x` in the gap before `review`: most forgetting happens early, then it flattens. */
export function fall(review: Review, x: number): number {
  const u = Math.min(1, Math.max(0, (x - review.from) / (review.x - review.from)));
  return chart.top + (chart.target - chart.top) * (1 - (1 - u) ** 1.8);
}

/** The curve's height at `x`: right after a review it's back at the top. */
export function recall(x: number): number {
  const gap = reviews.find((review) => x >= review.from && x < review.x) ?? reviews.at(-1);
  if (gap == null || x < learned) return chart.top;
  return fall(gap, x);
}

/** The curve's points from `from` to `to`, with each review's jump back to the top. */
function points(from: number, to: number): [number, number][] {
  const result: [number, number][] = [];
  for (const gap of reviews) {
    const start = Math.max(gap.from, from);
    const end = Math.min(gap.x, to);
    if (end <= start) continue;
    for (let x = start; x < end; x += 2) result.push([x, recall(x)]);
    if (end === gap.x) result.push([gap.x, chart.target], [gap.x, chart.top]);
    else result.push([end, recall(end)]);
  }
  return result;
}

const svgPoint = ([x, y]: [number, number]) => `${x.toFixed(1)} ${y.toFixed(1)}`;

/** The curve as a path, from `from` to `to`. */
export function curve(from: number, to: number): string {
  const [first, ...rest] = points(from, to);
  if (first == null) return "";
  return `M ${svgPoint(first)} ${rest.map((point) => `L ${svgPoint(point)}`).join(" ")}`;
}

/** The area under the curve, from `from` to `to`, closed at the chart's foot. */
export function area(from: number, to: number): string {
  const line = points(from, to);
  return `M ${from} ${chart.base} ${line.map((point) => `L ${svgPoint(point)}`).join(" ")} L ${to} ${chart.base} Z`;
}
