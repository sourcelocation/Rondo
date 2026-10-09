import { Play, SlidersHorizontal, Settings2, Trash2 } from "lucide-react";
import { useState } from "react";
import { Link, useParams } from "react-router";
import { ColorSwatches, EmojiPicker } from "@/components/decks";
import { confirm, Counts, Empty, Header, Loading, Section } from "@/components/kit";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { app, s, useScreen, type SmartDeckScreen, type SmartDeckState } from "@/rondo";
import { useOverlay, useUp } from "@/routing";
import { filterParams } from "@/screens/Browse";

type Look = { name: string; icon: string | null; color: number; newPerDay: number; reviewsPerDay: number };

/** Saves [change] on top of what the smart deck has now. */
const save = (smart: SmartDeckScreen, st: SmartDeckState, change: Partial<Look>) => {
  const v = {
    name: st.item.name,
    icon: st.item.icon ?? null,
    color: st.item.color,
    newPerDay: st.newPerDay,
    reviewsPerDay: st.reviewsPerDay,
    ...change,
  };
  smart.save(v.name, v.icon, v.color, v.newPerDay, v.reviewsPerDay);
};

/** A whole number from an input, kept within [min]…[max]; null while it's not a number. */
const whole = (text: string, min: number, max: number) => {
  const n = Number.parseInt(text, 10);
  return Number.isNaN(n) ? null : Math.min(max, Math.max(min, n));
};

/** Its look and daily limits; each change is saved as it's made. Its rules change in Browse. */
function SmartSettings({ st, smart, onClose }: { st: SmartDeckState; smart: SmartDeckScreen; onClose: () => void }) {
  const [name, setName] = useState(st.item.name);
  const [limits, setLimits] = useState({ n: String(st.newPerDay), r: String(st.reviewsPerDay) });
  const set = (change: Partial<Look>) => save(smart, st, change);
  return (
    <Sheet open onOpenChange={(o) => !o && onClose()}>
      <SheetContent className="w-full gap-0 overflow-y-auto sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{s.smartDeckSettings}</SheetTitle>
          <SheetDescription className="sr-only">{s.smartDeckSettings}</SheetDescription>
        </SheetHeader>
        <div className="grid gap-6 px-4 pb-8">
          <div className="flex items-center gap-3">
            <EmojiPicker value={st.item.icon ?? null} onChange={(icon) => set({ icon })} />
            <Input
              aria-label={s.deckName}
              value={name}
              maxLength={100}
              className="h-12"
              onChange={(e) => setName(e.target.value.replace(/[\t\n]/g, ""))}
              onBlur={() => (name.trim() && name !== st.item.name ? set({ name }) : setName(st.item.name))}
            />
          </div>
          <ColorSwatches value={st.item.color} onChange={(color) => set({ color })} />
          <div className="grid grid-cols-2 gap-3">
            <div className="grid gap-2">
              <Label>{s.newPerDay}</Label>
              <Input
                inputMode="numeric"
                value={limits.n}
                onChange={(e) => setLimits({ ...limits, n: e.target.value })}
                onBlur={() => {
                  const n = whole(limits.n, 0, 9999) ?? st.newPerDay;
                  setLimits({ ...limits, n: String(n) });
                  if (n !== st.newPerDay) set({ newPerDay: n });
                }}
              />
            </div>
            <div className="grid gap-2">
              <Label>{s.reviewsPerDay}</Label>
              <Input
                inputMode="numeric"
                value={limits.r}
                onChange={(e) => setLimits({ ...limits, r: e.target.value })}
                onBlur={() => {
                  const r = whole(limits.r, 0, 99999) ?? st.reviewsPerDay;
                  setLimits({ ...limits, r: String(r) });
                  if (r !== st.reviewsPerDay) set({ reviewsPerDay: r });
                }}
              />
            </div>
          </div>
        </div>
      </SheetContent>
    </Sheet>
  );
}

/** A smart deck: what it offers today, its rules (changed in Browse), its settings. */
export function SmartDeck() {
  const { id = "" } = useParams();
  const goUp = useUp();
  const [st, smart] = useScreen(() => app.smartDeck(id), [id]);
  const sheet = useOverlay("sheet");
  if (!st) return <Loading />;
  if (st.gone) return <Empty title={s.notFound}>{s.error("not_found")}</Empty>;
  const d = st.item;
  const due = d.newCount + d.learning + d.review;
  const rules = filterParams(st);
  rules.set("smart", id);
  return (
    <>
      <Header
        up="/"
        title={
          <span className="flex items-center gap-3">
            {d.icon && <span>{d.icon}</span>}
            <span className="truncate">{d.name}</span>
          </span>
        }
        sub={s.smartDecks}
      >
        <Button variant="ghost" size="icon" aria-label={s.smartDeckSettings} onClick={() => sheet.open("settings")}>
          <Settings2 />
        </Button>
      </Header>
      <p className="-mt-4 mb-6 text-subtle">{s.smartDeckHint}</p>
      <div className="mb-10 flex flex-col gap-2 sm:flex-row sm:items-center">
        {due > 0 ? (
          <Button size="lg" className="sm:flex-1" asChild>
            <Link to={`/smart/${id}/study`}>
              <Play /> {s.studyN(due)}
            </Link>
          </Button>
        ) : (
          <p className="flex h-10 items-center text-sm text-subtle sm:flex-1">{s.nothingDue}</p>
        )}
        {due > 0 && <Counts n={d.newCount} l={d.learning} r={d.review} />}
      </div>
      <Section
        title={s.smartRules}
        action={
          <Button variant="ghost" size="sm" asChild>
            <Link to={`/browse?${rules}`}>
              <SlidersHorizontal /> {s.editRules}
            </Link>
          </Button>
        }
      >
        <ul className="divide-y overflow-hidden rounded-lg bg-card shadow-sm">
          {st.rules.map((r) => (
            <li key={r} className="px-4 py-3">
              {r}
            </li>
          ))}
        </ul>
      </Section>
      <Button
        variant="ghost"
        className="text-destructive"
        onClick={async () => {
          if (await confirm(`${s.delete} “${d.name}”?`, s.deleteSmartDeckHint)) {
            smart.delete();
            goUp("/");
          }
        }}
      >
        <Trash2 /> {s.delete}
      </Button>
      {sheet.value === "settings" && <SmartSettings st={st} smart={smart} onClose={sheet.close} />}
    </>
  );
}
