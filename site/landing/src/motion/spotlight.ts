/**
 * Cards lit from where the pointer is: each [data-spotlight] gets the pointer's place over it as --x and --y, which
 * its styles draw a soft light at.
 */
export function spotlight(cards: readonly HTMLElement[]): void {
  for (const card of cards) {
    card.addEventListener("pointermove", (event) => {
      if (event.pointerType !== "mouse") return;
      const box = card.getBoundingClientRect();
      card.style.setProperty("--x", `${String(Math.round(event.clientX - box.left))}px`);
      card.style.setProperty("--y", `${String(Math.round(event.clientY - box.top))}px`);
    });
  }
}
