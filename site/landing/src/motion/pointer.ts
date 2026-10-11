/**
 * Where the pointer is over the window, for every scene on the page to read on its own frames (each eases toward it
 * at its own pace): `x` and `y` run from -0.5 to 0.5 out from the window's middle. One listener for the page, rather
 * than one a scene. Scenes that move on their own leave it alone with reduced motion; what follows the pointer on
 * request still can.
 */
export class Pointer {
  x = 0;
  y = 0;
  clientX = window.innerWidth / 2;
  clientY = window.innerHeight / 2;
  /** Whether the pointer that moved last was a mouse (or a trackpad), rather than a finger or a pen. */
  mouse = false;

  constructor() {
    window.addEventListener(
      "pointermove",
      (event) => {
        this.clientX = event.clientX;
        this.clientY = event.clientY;
        this.x = event.clientX / window.innerWidth - 0.5;
        this.y = event.clientY / window.innerHeight - 0.5;
        this.mouse = event.pointerType === "mouse";
      },
      { passive: true },
    );
  }
}
