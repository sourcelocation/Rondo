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
import { app, Report, s } from "@/rondo";

const reasons = ["spam", "harassment", "hate", "sexual", "impersonation", "illegal", "other"];
const laws = ["copyright", "child_safety", "terrorism", "privacy", "other_law"];

const Pick = ({
  value,
  options,
  label,
  onChange,
}: {
  value: string;
  options: string[];
  label: (o: string) => string;
  onChange: (o: string) => void;
}) => (
  <ToggleGroup
    type="single"
    variant="outline"
    className="flex-wrap justify-start"
    value={value}
    onValueChange={(v) => v && onChange(v)}
  >
    {options.map((o) => (
      <ToggleGroupItem key={o} value={o} className="px-3">
        {label(o)}
      </ToggleGroupItem>
    ))}
  </ToggleGroup>
);

/**
 * Reporting a deck or a person: what's wrong, and for illegal content which law, why, and that
 * it's in good faith (what a legal notice needs).
 */
export function ReportDialog({ kind, id, children }: { kind: "deck" | "person"; id: string; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [law, setLaw] = useState("");
  const [note, setNote] = useState("");
  const [goodFaith, setGoodFaith] = useState(false);
  const illegal = reason === "illegal";
  const ready = reason !== "" && (!illegal || (law !== "" && note.trim().length >= 10 && goodFaith));
  const reset = () => (setReason(""), setLaw(""), setNote(""), setGoodFaith(false));
  return (
    <Dialog open={open} onOpenChange={(o) => (setOpen(o), o || reset())}>
      <DialogTrigger asChild>{children}</DialogTrigger>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{s.reportTitle}</DialogTitle>
          <DialogDescription>{s.reportWhat}</DialogDescription>
        </DialogHeader>
        <form
          className="grid gap-5"
          onSubmit={(e) => {
            e.preventDefault();
            if (!ready) return;
            app.report(new Report(kind, id, reason, illegal ? law : null, note, illegal && goodFaith), () => {
              setOpen(false);
              reset();
            });
          }}
        >
          <Pick value={reason} options={reasons} label={(r) => s.reportReason(r)} onChange={setReason} />
          {illegal && (
            <div className="grid gap-2">
              <Label>{s.reportWhichLaw}</Label>
              <Pick value={law} options={laws} label={(l) => s.law(l)} onChange={setLaw} />
            </div>
          )}
          {reason && (
            <div className="grid gap-2">
              <Label htmlFor="report-note">{illegal ? s.reportWhy : s.reportDetails}</Label>
              <Textarea id="report-note" value={note} maxLength={2000} onChange={(e) => setNote(e.target.value)} />
            </div>
          )}
          {illegal && (
            <label className="flex items-start gap-3 text-sm">
              <Checkbox checked={goodFaith} onCheckedChange={(v) => setGoodFaith(v === true)} />
              {s.reportGoodFaith}
            </label>
          )}
          <DialogFooter>
            <Button type="submit" disabled={!ready}>
              {s.report}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
