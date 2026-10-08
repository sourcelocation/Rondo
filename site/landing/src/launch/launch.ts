import { PUBLIC_RONDO_RELEASE } from "astro:env/client";

/**
 * Rondo's launch. Until it, the site offers a star on GitHub and one email on launch day in place of the apps; from
 * then on, the apps. The clock decides, unless PUBLIC_RONDO_RELEASE holds the site at "pre" or "live" (a launch that
 * slips, or a preview). /launch.js puts the answer on <html> before a page paints, and the styles read it.
 */

/** Thursday 22 October 2026, 18:00 in its maker's time zone (UTC+3). */
export const LAUNCH = Date.parse("2026-10-22T18:00:00+03:00");

/** The maker's time zone, which pages built ahead of the visitor's clock state the launch in. */
const MAKER_ZONE = "Etc/GMT-3";

export type Release = "pre" | "live";

/** Whether Rondo is out at `time`, as the site's setting has it. */
export function releaseAt(time: number): Release {
  if (PUBLIC_RONDO_RELEASE !== "auto") return PUBLIC_RONDO_RELEASE;
  return time >= LAUNCH ? "live" : "pre";
}

/** Whether the clock decides, so a page open at the launch should turn to the apps then. */
export const followsClock = PUBLIC_RONDO_RELEASE === "auto";

/** English as the visitor writes it ("en-GB" puts the day first), or plain English. */
function locale(): string {
  const language = typeof navigator === "undefined" ? "en" : navigator.language;
  return language.startsWith("en") ? language : "en";
}

function format(options: Intl.DateTimeFormatOptions, inMakerZone: boolean): string {
  const zone = inMakerZone ? { timeZone: MAKER_ZONE } : {};
  return new Intl.DateTimeFormat(locale(), { ...options, ...zone }).format(LAUNCH);
}

/** The launch day, as "October 22": in the maker's time zone when built, in the visitor's in their browser. */
export function launchDay(inMakerZone = false): string {
  return format({ month: "long", day: "numeric" }, inMakerZone);
}

/** The launch, as "Thursday, October 22 at 6:00 PM". */
export function launchMoment(inMakerZone = false): string {
  return format({ weekday: "long", month: "long", day: "numeric", hour: "numeric", minute: "2-digit" }, inMakerZone);
}
