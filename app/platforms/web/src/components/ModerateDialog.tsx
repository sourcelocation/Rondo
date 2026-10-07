import { useState, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { s } from "@/rondo";

/** The reasons staff give (the server's list); people are told them. */
export const reasons = [
  "spam",
  "harassment",
  "hate",
  "sexual",
  "impersonation",
  "copyright",
  "illegal",
  "name",
  "evasion",
  "other",
];

const lengths = [1, 7, 30, 0];

const Choices = <T extends string | number>({
  value,
  options,
  label,
  onChange,
}: {
  value: T;
  options: T[];
  label: (v: T) => string;
  onChange: (v: T) => void;
}) => (
  <ToggleGroup
    type="single"
    variant="outline"
    className="flex-wrap justify-start"
    value={String(value)}
    onValueChange={(v) => v && onChange(options.find((o) => String(o) === v)!)}
  >
    {options.map((o) => (
      <ToggleGroupItem key={String(o)} value={String(o)} className="px-3">
        {label(o)}
      </ToggleGroupItem>
    ))}
  </ToggleGroup>
);

/** A reason and a note the person sees: what every moderation form asks. */
function Reason({
  reason,
  note,
  onReason,
  onNote,
}: {
  reason: string;
  note: string;
  onReason: (r: string) => void;
  onNote: (n: string) => void;
}) {
  return (
    <>
      <div className="grid gap-2">
        <Label>{s.reasonLabel}</Label>
        <Choices value={reason} options={reasons} label={(r) => s.reason(r)} onChange={onReason} />
      </div>
      <div className="grid gap-2">
        <Label htmlFor="moderate-note">{s.noteLabel}</Label>
        <Textarea id="moderate-note" value={note} maxLength={2000} onChange={(e) => onNote(e.target.value)} />
        <p className="text-xs text-muted-foreground">{s.noteHint}</p>
      </div>
    </>
  );
}

/**
 * One form for every step against a person: what (the ladder's next step first), why, a note they
 * see, and for how long. [kinds] are the steps the viewer may take.
 */
export function ModerateDialog({
  kinds,
  suggestedKind,
  suggestedDays,
  onSubmit,
  children,
}: {
  kinds: string[];
  suggestedKind: string;
  suggestedDays: number | null;
  onSubmit: (kind: string, reason: string | null, note: string, days: number) => void;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const [kind, setKind] = useState(kinds.includes(suggestedKind) ? suggestedKind : (kinds[0] ?? ""));
  const [reason, setReason] = useState("spam");
  const [note, setNote] = useState("");
  const [days, setDays] = useState(suggestedDays ?? 0);
  const timed = kind === "user.restrict" || kind === "user.ban";
  const reasoned = kind !== "user.lift";
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>{children}</DialogTrigger>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{s.moderate}</DialogTitle>
          <DialogDescription>
            {s.suggested}: {s.moderationKind(suggestedKind)}
            {suggestedKind === "user.restrict" && ` · ${suggestedDays ? s.forDays(suggestedDays) : s.forGood}`}
          </DialogDescription>
        </DialogHeader>
        <form
          className="grid gap-5"
          onSubmit={(e) => {
            e.preventDefault();
            onSubmit(kind, reasoned ? reason : null, note, timed ? days : 0);
            setOpen(false);
          }}
        >
          <Choices value={kind} options={kinds} label={(k) => s.moderationKind(k)} onChange={setKind} />
          {reasoned && <Reason reason={reason} note={note} onReason={setReason} onNote={setNote} />}
          {timed && (
            <div className="grid gap-2">
              <Label>{s.howLong}</Label>
              <Choices
                value={days}
                options={lengths}
                label={(d) => (d ? s.forDays(d) : s.forGood)}
                onChange={setDays}
              />
            </div>
          )}
          <DialogFooter>
            <Button type="submit" variant={kind === "user.ban" ? "destructive" : "default"}>
              {s.moderationKind(kind)}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/** Taking a deck off Discover: why, a note for its author, and whether its followers lose it too. */
export function RemoveDeckDialog({
  onSubmit,
  children,
}: {
  onSubmit: (reason: string, note: string, takeDown: boolean) => void;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("spam");
  const [note, setNote] = useState("");
  const [takeDown, setTakeDown] = useState(false);
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>{children}</DialogTrigger>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{s.removeFromDiscover}</DialogTitle>
        </DialogHeader>
        <form
          className="grid gap-5"
          onSubmit={(e) => (e.preventDefault(), onSubmit(reason, note, takeDown), setOpen(false))}
        >
          <Reason reason={reason} note={note} onReason={setReason} onNote={setNote} />
          <label className="flex items-start gap-3">
            <Checkbox checked={takeDown} onCheckedChange={(v) => setTakeDown(v === true)} />
            <span className="grid gap-0.5">
              <span className="text-sm font-medium">{s.takeDown}</span>
              <span className="text-xs text-muted-foreground">{s.takeDownHint}</span>
            </span>
          </label>
          <DialogFooter>
            <Button type="submit" variant="destructive">
              {s.removeFromDiscover}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
