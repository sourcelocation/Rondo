import type { Screen } from "./screen";

/**
 * Where the pointer is over the screen, for every scene on the page to read on its own frames (each eases toward it
 * at its own pace): `x` and `y` run from -0.5 to 0.5 out from the middle of what's always seen. One listener for the
 * page, rather than one a scene. Scenes that move on their own leave it alone with reduced motion; what follows the
 * pointer on request still can.
 */
export class Pointer {
  x = 0;
  y = 0;
  clientX: number;
  clientY: number;
  /** Whether the pointer that moved last was a mouse (or a trackpad), rather than a finger or a pen. */
  mouse = false;

  constructor(screen: Screen) {
    this.clientX = screen.width / 2;
    this.clientY = screen.safe / 2;
    window.addEventListener(
      "pointermove",
      (event) => {
        this.clientX = event.clientX;
        this.clientY = event.clientY;
        this.x = event.clientX / screen.width - 0.5;
        this.y = event.clientY / screen.safe - 0.5;
        this.mouse = event.pointerType === "mouse";
      },
      { passive: true },
    );
  }
}
