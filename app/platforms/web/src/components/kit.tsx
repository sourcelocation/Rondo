import { ArrowLeft, Loader2, Lock, type LucideIcon } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import { Link } from "react-router";
import mark from "../../../../../brand/dist/svg/mark.svg";
import markOnDark from "../../../../../brand/dist/svg/mark-on-dark.svg";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { s } from "@/rondo";
import { useUp } from "@/routing";
import { cn } from "cn";

/** Rondo's mark: ink on paper, reversed on dark. */
export const Mark = ({ className }: { className?: string }) => (
  <>
    <img src={mark} alt="" className={cn(className, "dark:hidden")} />
    <img src={markOnDark} alt="" className={cn(className, "hidden dark:block")} />
  </>
);

/** The mark and the name, set in Juana: for headers. */
export const Wordmark = ({ className }: { className?: string }) => (
  <span className={cn("inline-flex items-center gap-2", className)}>
    <Mark className="h-5 w-auto" />
    <span className="font-serif text-[1.375rem] leading-none">{s.appName}</span>
  </span>
);

/** A page's title row: a way up to [up] (the page above), a line under the title, actions on the right. */
export function Header({
  title,
  sub,
  up,
  children,
}: {
  title: ReactNode;
  sub?: ReactNode;
  up?: string;
  children?: ReactNode;
}) {
  const goUp = useUp();
  return (
    <header className="mb-8 flex items-start gap-3">
      {up !== undefined && (
        <Button variant="ghost" size="icon" aria-label={s.back} className="-ml-2 mt-1" onClick={() => goUp(up)}>
          <ArrowLeft />
        </Button>
      )}
      <div className="min-w-0 flex-1">
        <h1 className="truncate">{title}</h1>
        {sub && <div className="mt-1 text-subtle">{sub}</div>}
      </div>
      {children && <div className="flex shrink-0 flex-wrap items-center justify-end gap-2 pt-1">{children}</div>}
    </header>
  );
}

/** A titled group on a page, like a grouped list section on the phones. */
export const Section = ({
  title,
  children,
  action,
  className,
}: {
  title?: ReactNode;
  children: ReactNode;
  action?: ReactNode;
  className?: string;
}) => (
  <section className={cn("mb-10", className)}>
    {(title || action) && (
      <div className="mb-3 flex min-h-8 items-center justify-between gap-3">
        <h2 className="text-xs font-medium tracking-wider text-muted-foreground uppercase">{title}</h2>
        {action && <div className="flex items-center gap-1">{action}</div>}
      </div>
    )}
    {children}
  </section>
);

/** Nothing here yet, and what to do about it. */
export const Empty = ({
  icon: Icon,
  title,
  children,
  action,
}: {
  icon?: LucideIcon;
  title?: ReactNode;
  children?: ReactNode;
  action?: ReactNode;
}) => (
  <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed px-6 py-10 text-center">
    {Icon && <Icon className="mb-2 size-7 text-muted-foreground" />}
    {title && <h2>{title}</h2>}
    {children && <p className="max-w-sm text-sm text-subtle">{children}</p>}
    {action && <div className="mt-3">{action}</div>}
  </div>
);

export const Spinner = ({ className }: { className?: string }) => (
  <Loader2 className={cn("size-4 animate-spin", className)} aria-label={s.loading} />
);

/** While a screen's data loads. */
export const Loading = () => (
  <div className="grid min-h-64 place-items-center">
    <Spinner className="size-6 text-muted-foreground" />
  </div>
);

/** The whole window while Rondo starts. */
export const Splash = () => (
  <div className="grid min-h-dvh place-items-center bg-background">
    <Mark className="h-10 w-auto animate-pulse" />
  </div>
);

/** A whole-window message with the mark: another tab, a missing page. */
const Notice = ({ title, children, action }: { title: string; children: ReactNode; action: ReactNode }) => (
  <main className="grid min-h-dvh place-items-center bg-background px-6">
    <div className="flex max-w-sm flex-col items-center gap-4 text-center">
      <Mark className="h-10 w-auto" />
      <h1 className="text-2xl">{title}</h1>
      <p className="text-subtle">{children}</p>
      {action}
    </div>
  </main>
);

/** Shown while another tab has Rondo open, with a way to move it here. */
export const OtherTab = ({ onUseHere }: { onUseHere: () => void }) => (
  <Notice title={s.otherTab} action={<Button onClick={onUseHere}>{s.useHere}</Button>}>
    {s.otherTabHint}
  </Notice>
);

export const NotFound = () => (
  <Notice
    title={s.notFound}
    action={
      <Button asChild>
        <Link to="/">{s.goHome}</Link>
      </Button>
    }
  >
    {s.notFoundHint}
  </Notice>
);

/** Progress as a ring: learned percent, or a locked level's way to opening. */
export function Ring({ value, locked, size = 36 }: { value: number; locked?: boolean; size?: number }) {
  const r = size / 2 - 3;
  const c = 2 * Math.PI * r;
  return (
    <span
      className="relative inline-flex shrink-0 items-center justify-center"
      style={{ width: size, height: size }}
      title={`${value}%`}
    >
      <svg width={size} height={size} className="-rotate-90">
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" strokeWidth={3} className="stroke-muted" />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={r}
          fill="none"
          strokeWidth={3}
          strokeLinecap="round"
          strokeDasharray={c}
          strokeDashoffset={c * (1 - value / 100)}
          className={cn(
            "transition-[stroke-dashoffset] duration-500",
            locked ? "stroke-paused" : value >= 100 ? "stroke-review" : "stroke-primary",
          )}
        />
      </svg>
      {locked ? (
        <Lock className="absolute size-3.5 text-muted-foreground" />
      ) : (
        <span className="absolute font-sans text-[10px] font-medium tabular-nums">{value}</span>
      )}
    </span>
  );
}

/** New, learning and review counts, coloured, with what they mean for screen readers. */
export const Counts = ({ n, l, r }: { n: number; l: number; r: number }) => (
  <span className="flex gap-2 text-sm tabular-nums" title={s.counts(n, l, r)} aria-label={s.counts(n, l, r)}>
    <span className={n ? "text-new" : "text-muted-foreground"}>{n}</span>
    <span className={l ? "text-learning" : "text-muted-foreground"}>{l}</span>
    <span className={r ? "text-review" : "text-muted-foreground"}>{r}</span>
  </span>
);

/** A deck's colour (1–7; 0 is none) as a CSS colour. */
export const DECK_COLORS = [
  "",
  "--rd-color-content-red",
  "--rd-color-content-orange",
  "--rd-color-content-yellow",
  "--rd-color-content-green",
  "--rd-color-content-blue",
  "--rd-color-content-purple",
  "--rd-color-flag-pink",
];
export const deckColor = (n: number) => (DECK_COLORS[n] ? `var(${DECK_COLORS[n]})` : undefined);

/** A deck's emoji, or its colour as a dot. */
export const DeckLook = ({ icon, color, className }: { icon?: string | null; color: number; className?: string }) =>
  icon ? (
    <span className={cn("w-5 shrink-0 text-center", className)}>{icon}</span>
  ) : (
    <span className={cn("grid w-5 shrink-0 place-items-center", className)}>
      <span
        className="size-2.5 rounded-full bg-muted-foreground/40"
        style={deckColor(color) ? { background: deckColor(color) } : undefined}
      />
    </span>
  );

type Request =
  | { kind: "ask"; title: string; value: string; done: (v: string | null) => void }
  | { kind: "confirm"; title: string; body?: string; action: string; destructive: boolean; done: (v: boolean) => void }
  | { kind: "pick"; title: string; options: { id: string; label: string }[]; done: (v: string | null) => void };

let show: (r: Request | null) => void = () => {};

/** Asks for a line of text: a name. */
export const ask = (title: string, value = "") =>
  new Promise<string | null>((done) => show({ kind: "ask", title, value, done }));

/** Asks before something that's hard to take back; [destructive] colours the action red. */
export const confirm = (title: string, body?: string, action = s.delete, destructive = true) =>
  new Promise<boolean>((done) => show({ kind: "confirm", title, body, action, destructive, done }));

/** Picks one of [options], searchable: where to move something. */
export const pick = (title: string, options: { id: string; label: string }[]) =>
  new Promise<string | null>((done) => show({ kind: "pick", title, options, done }));

/** Shows what [ask], [confirm] and [pick] ask for. Mounted once. */
export function Dialogs() {
  const [r, setR] = useState<Request | null>(null);
  const [text, setText] = useState("");
  useEffect(() => {
    show = (next) => {
      setR(next);
      if (next?.kind === "ask") setText(next.value);
      if (next?.kind === "pick") setText("");
    };
  }, []);
  const end = (value: string | boolean | null) => {
    if (!r) return;
    (r.done as (v: typeof value) => void)(value);
    setR(null);
  };
  if (r?.kind === "confirm")
    return (
      <AlertDialog open onOpenChange={(o) => !o && end(false)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{r.title}</AlertDialogTitle>
            {r.body && <AlertDialogDescription>{r.body}</AlertDialogDescription>}
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{s.cancel}</AlertDialogCancel>
            <AlertDialogAction
              className={cn(r.destructive && "bg-destructive text-white hover:bg-destructive/90")}
              onClick={() => end(true)}
            >
              {r.action}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    );
  const shown = r?.kind === "pick" ? r.options.filter((o) => o.label.toLowerCase().includes(text.toLowerCase())) : [];
  return (
    <Dialog open={!!r} onOpenChange={(o) => !o && end(null)}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{r?.title}</DialogTitle>
        </DialogHeader>
        {r?.kind === "ask" && (
          <form onSubmit={(e) => (e.preventDefault(), text.trim() && end(text.trim()))} className="contents">
            <Input autoFocus value={text} onChange={(e) => setText(e.target.value)} />
            <DialogFooter>
              <Button type="button" variant="ghost" onClick={() => end(null)}>
                {s.cancel}
              </Button>
              <Button type="submit" disabled={!text.trim()}>
                {s.save}
              </Button>
            </DialogFooter>
          </form>
        )}
        {r?.kind === "pick" && (
          <form onSubmit={(e) => (e.preventDefault(), shown[0] && end(shown[0].id))} className="contents">
            <Input autoFocus placeholder={s.search} value={text} onChange={(e) => setText(e.target.value)} />
            <div className="-mx-2 max-h-80 overflow-y-auto">
              {shown.map((o) => (
                <button
                  type="button"
                  key={o.id}
                  className="block w-full rounded-md px-3 py-2 text-left hover:bg-accent focus-visible:bg-accent focus-visible:outline-none"
                  onClick={() => end(o.id)}
                >
                  {o.label}
                </button>
              ))}
            </div>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
