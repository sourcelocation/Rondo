/**
 * The page past the screen's foot. A phone's browser draws the page's words on under its toolbar, but nothing that's
 * held on the screen, so the scenery stops short of it: behind the words there, it shows <html>'s colour (--edge,
 * styles/base.css). The light (motion/scenery/light.ts) keeps that the colour along its foot as it changes, so the
 * scenery seems to carry on under the browser. (At the top, the bar's frosted glass carries on under the status bar on
 * its own: the browser takes it for a bar of the page's.) Without the light, it's the page's paper.
 */
export class Edges {
  constructor(private readonly root: HTMLElement) {}

  /** Carries `colour` on past the screen's foot. */
  paint(colour: string): void {
    this.root.style.setProperty("--edge", colour);
  }
}
