/**
 * The collection's deck as it's seen: its cards stood square one behind the other, faces to the viewer, seen straight
 * on from a little above, in perspective. Each card back looks a little smaller and stands a little higher, toward
 * where the deck's edges run to, above its middle, so the edges of the cards behind the front one show over it as a
 * band, the deck's top, narrowing away as a trapezium does; the further back a card stands, the darker it is, and the
 * last few fade into the distance. The scenery's deck (motion/scenery/pile.ts) and the page's still one
 * (components/Collection.astro) are both seen this way.
 */
export class Stack {
  /** How many cards deep the deck shows; those further back are lost in the distance. */
  readonly depth = 20;
  /** How far above the deck's middle the eye is, in card heights: the deck's edges run there. */
  readonly horizon = 1.34;
  /** How much smaller each card looks than the one in front of it. */
  private readonly recede = 0.0075;
  /** How much darker the last card is than the front one, from 0 to 1. */
  private readonly dim = 0.2;
  /** How many cards at the back fade into the distance. */
  private readonly haze = 4;

  /** How big a card `depth` cards back looks, against the front one. */
  scale(depth: number): number {
    return 1 / (1 + depth * this.recede);
  }

  /** How much darker a card `depth` cards back is, from 0 to 1. */
  shade(depth: number): number {
    return this.dim * Math.min(depth / this.depth, 1);
  }

  /** How much there a card `depth` cards back is, from 0 to 1: the last few fade into the distance. */
  presence(depth: number): number {
    return Math.min(Math.max((this.depth - depth) / this.haze, 0), 1);
  }
}
