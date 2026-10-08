import { gsap } from "gsap";
import { one } from "../motion/dom";
import { launchDay } from "./launch";

/** Where this browser remembers that its visitor is on the list (and as whom). */
const KEY = "rondo:launch-list";
/** Told to every form on the page when the visitor joins through any of them. */
const JOINED = "rondo:joined";

const ADDRESS = /^[^\s@]+@[^\s@.]+(\.[^\s@.]+)+$/u;

const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function remembered(): string | null {
  try {
    return localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

function remember(email: string): void {
  try {
    localStorage.setItem(KEY, email);
  } catch {
    // Without storage, the visitor is still on the list; only this browser forgets it.
  }
}

/** The server's refusal, in words for the visitor: an address it won't take, or too many sign-ups from here. */
class Refused extends Error {}

/**
 * Puts an address on the launch list, for one email on launch day and nothing else. The server hands it to Listmonk,
 * which emails a link to confirm it. Takes at least as long as the send button's spinner needs to be seen.
 */
export async function joinLaunchList(email: string): Promise<void> {
  const [response] = await Promise.all([
    fetch("/api/launch-list", {
      method: "POST",
      headers: { "Content-Type": "application/json", "Rondo-Protocol": "1" },
      body: JSON.stringify({ email }),
    }),
    pause(700),
  ]);
  if (!response.ok) {
    const problem: unknown = await response.json().catch(() => null);
    const refusal = response.status === 400 || response.status === 429 || response.status === 503;
    if (refusal && problem instanceof Object && "message" in problem && typeof problem.message === "string") {
      throw new Refused(problem.message);
    }
    throw new Error(`The launch list answered ${String(response.status)}`);
  }
  remember(email);
}

/** "m•••@gmail.com", so the confirmation shows whose address it was without showing all of it. */
function masked(email: string): string {
  const [name = "", domain = ""] = email.split("@");
  return `${name.slice(0, 1)}•••@${domain}`;
}

/**
 * A launch-list form. It checks the address as it's typed (the send button turns to ink when it looks complete),
 * says what's wrong in place of its note, and once sent turns into a confirmation, together with every other form
 * on the page and on later visits.
 */
export class NotifyForm {
  private readonly input: HTMLInputElement;
  private readonly field: HTMLElement;
  private readonly done: HTMLElement;
  private readonly note: HTMLElement;
  private readonly address: HTMLElement;
  private readonly tick: SVGPathElement;
  private readonly noteText: string;

  constructor(private readonly form: HTMLFormElement) {
    this.input = one(form, "[data-notify-input]", HTMLInputElement);
    this.field = one(form, ".notify__field");
    this.done = one(form, "[data-notify-done]");
    this.note = one(form, "[data-notify-note]");
    this.address = one(form, "[data-notify-address]");
    this.tick = one(form, "[data-notify-tick]", SVGPathElement);
    this.noteText = this.note.innerHTML;

    form.addEventListener("submit", (event) => {
      event.preventDefault();
      void this.send();
    });
    this.input.addEventListener("input", () => {
      form.dataset.ready = String(ADDRESS.test(this.input.value.trim()));
      if (form.dataset.state === "invalid" || form.dataset.state === "failed") this.state("idle", this.noteText);
    });
    document.addEventListener(JOINED, (event) => {
      if (event instanceof CustomEvent && typeof event.detail === "string") this.joined(event.detail, true);
    });
    const email = remembered();
    if (email != null) this.joined(email, false);
  }

  private async send(): Promise<void> {
    const state = this.form.dataset.state;
    if (state === "sending" || state === "done") return;
    const email = this.input.value.trim();
    if (!ADDRESS.test(email)) {
      this.refuse(email.length === 0 ? "Your email, please." : "That doesn’t look like a whole email address.");
      return;
    }
    this.state("sending");
    this.input.readOnly = true;
    try {
      await joinLaunchList(email);
      document.dispatchEvent(new CustomEvent(JOINED, { detail: email }));
    } catch (error) {
      this.input.readOnly = false;
      const message = error instanceof Refused ? error.message : "Something went wrong on our side. Please try again.";
      this.refuse(message, "failed");
    }
  }

  /** Says what's wrong where the note was, and nudges the field. */
  private refuse(message: string, state: "invalid" | "failed" = "invalid"): void {
    this.state(state, message);
    this.input.setAttribute("aria-invalid", "true");
    this.input.focus();
    gsap.fromTo(this.field, { x: 0 }, { x: 0, duration: 0.5, ease: "none", keyframes: { x: [0, -7, 6, -4, 2, 0] } });
  }

  private state(state: string, note?: string): void {
    this.form.dataset.state = state;
    if (state !== "invalid" && state !== "failed") this.input.removeAttribute("aria-invalid");
    if (note != null && this.note.textContent !== note) {
      gsap.fromTo(this.note, { opacity: 0, y: -4 }, { opacity: 1, y: 0, duration: 0.45, ease: "power2.out" });
      if (note === this.noteText) this.note.innerHTML = note;
      else this.note.textContent = note;
    }
  }

  /** On the list, once confirmed: the field gives way to the confirmation, the tick drawing itself in. */
  private joined(email: string, animate: boolean): void {
    if (this.form.dataset.state === "done") return;
    this.state("done");
    this.address.textContent = `Confirm with the link sent to ${masked(email)}. Then one email on ${launchDay()}, and nothing else.`;
    document.documentElement.dataset.listed = "";
    this.done.hidden = false;
    this.input.tabIndex = -1;
    if (!animate) {
      this.field.hidden = true;
      return;
    }
    const length = this.tick.getTotalLength();
    gsap
      .timeline({ onComplete: () => (this.field.hidden = true) })
      .to(this.field, { opacity: 0, scale: 0.97, filter: "blur(8px)", duration: 0.4, ease: "power2.in" })
      .fromTo(
        this.done,
        { opacity: 0, scale: 0.96, filter: "blur(10px)" },
        { opacity: 1, scale: 1, filter: "blur(0px)", duration: 0.8, ease: "expo.out", clearProps: "filter" },
        0.2,
      )
      .fromTo(
        this.tick,
        { strokeDasharray: length, strokeDashoffset: length },
        { strokeDashoffset: 0, duration: 0.6, ease: "power2.out" },
        0.45,
      );
  }
}

/**
 * The bar's launch-list panel: placed under its button as it opens (it lives in the top layer, outside the bar), it
 * hands the keyboard to the address field, and its button says "On the list" once the visitor is.
 */
export class NotifyPanel {
  private readonly trigger: HTMLElement | null;

  constructor(private readonly panel: HTMLElement) {
    this.trigger = document.querySelector<HTMLElement>(`[popovertarget="${panel.id}"]`);
    panel.addEventListener("beforetoggle", (event) => {
      if (event.newState === "open") this.place();
    });
    panel.addEventListener("toggle", (event) => {
      if (event.newState !== "open") {
        // Closed from the keyboard, focus goes back to the button rather than to the page.
        if (document.activeElement === document.body) this.trigger?.focus({ preventScroll: true });
        return;
      }
      const input = panel.querySelector<HTMLInputElement>("[data-notify-input]");
      if (input != null && input.tabIndex >= 0) input.focus({ preventScroll: true });
    });
    window.addEventListener("resize", () => {
      if (panel.matches(":popover-open")) this.place();
    });
    document.addEventListener(JOINED, () => this.listed());
    if (remembered() != null) this.listed();
  }

  private place(): void {
    if (this.trigger == null) return;
    const button = this.trigger.getBoundingClientRect();
    const width = document.documentElement.clientWidth;
    const style = this.panel.style;
    style.setProperty("--top", `${String(Math.round(button.bottom + 10))}px`);
    style.setProperty("--right", `${String(Math.max(16, Math.round(width - button.right)))}px`);
    style.setProperty("--from-width", `${String(Math.round(button.width))}px`);
    style.setProperty("--from-height", `${String(Math.round(button.height))}px`);
  }

  private listed(): void {
    if (this.trigger == null) return;
    for (const label of this.trigger.querySelectorAll("[data-notify-trigger-label]")) label.textContent = "On the list";
    this.trigger.setAttribute("aria-label", "You’re on the launch list");
  }
}
