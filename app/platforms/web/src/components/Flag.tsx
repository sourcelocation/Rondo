import { countries } from "country-flag-icons";
import { useEffect, useState, type ComponentType } from "react";
import { cn } from "cn";

type Flags = Record<string, ComponentType<{ title?: string; className?: string }>>;

// The flags are SVGs (Windows draws flag emoji as two letters), loaded once, the first time one shows.
let loaded: Flags | null = null;
const loading = () => import("country-flag-icons/react/3x2").then((m) => (loaded = m as unknown as Flags));

/** A country's name in the reader's language. */
export const countryName = (code: string) =>
  new Intl.DisplayNames([navigator.language], { type: "region" }).of(code) ?? code;

/** Countries someone can pick a flag of (the server takes these), by name. */
export const flagCodes = () =>
  countries
    .filter((c) => /^[A-Z]{2}$/.test(c) && !["EU", "XA", "XC", "XO"].includes(c))
    .sort((a, b) => countryName(a).localeCompare(countryName(b)));

/** A country's flag, beside a name. */
export function Flag({ code, className }: { code: string | null | undefined; className?: string }) {
  const [flags, setFlags] = useState(loaded);
  useEffect(() => {
    if (!flags && code) void loading().then(setFlags);
  }, [flags, code]);
  const Svg = code ? flags?.[code] : undefined;
  if (!code) return null;
  return Svg ? (
    <Svg
      title={countryName(code)}
      className={cn("inline-block h-[0.8em] w-auto rounded-[2px] align-baseline", className)}
    />
  ) : (
    <span className={cn("inline-block h-[0.8em] w-[1.2em] rounded-[2px] bg-muted align-baseline", className)} />
  );
}
