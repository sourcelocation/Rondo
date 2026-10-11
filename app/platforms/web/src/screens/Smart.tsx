import { Play, Settings2, Trash2 } from "lucide-react";
import { useState } from "react";
import { Link, useParams } from "react-router";
import { ColorSwatches, EmojiPicker } from "@/components/decks";
import { confirm, Counts, Empty, Header, Loading, Section, WholeInput } from "@/components/kit";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { app, s, usePart, useScreen, type SmartDeckScreen, type SmartDeckState } from "@/rondo";
import { useOverlay, useUp } from "@/routing";
import { FilterBar, NoteList, SortMenu } from "@/screens/Browse";

/** Its look and daily limits; each change is saved as it's made. */
function SmartSettings({ st, smart, onClose }: { st: SmartDeckState; smart: SmartDeckScreen; onClose: () => void }) {
  const [name, setName] = useState(st.item.name);
  return (
    <Sheet open onOpenChange={(o) => !o && onClose()}>
      <SheetContent className="w-full gap-0 overflow-y-auto sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{s.smartDeckSettings}</SheetTitle>
          <SheetDescription className="sr-only">{s.smartDeckSettings}</SheetDescription>
        </SheetHeader>
        <div className="grid gap-6 px-4 pb-8">
          <div className="flex items-center gap-3">
            <EmojiPicker value={st.item.icon ?? null} onChange={(icon) => smart.setIcon(icon)} />
            <Input
              aria-label={s.deckName}
              value={name}
              maxLength={100}
              className="h-12"
              onChange={(e) => setName(e.target.value.replace(/[\t\n]/g, ""))}
              onBlur={() => (name.trim() && name !== st.item.name ? smart.setName(name) : setName(st.item.name))}
            />
          </div>
          <ColorSwatches value={st.item.color} onChange={(color) => smart.setColor(color)} />
          <div className="grid grid-cols-2 gap-3">
            <div className="grid gap-2">
              <Label>{s.newPerDay}</Label>
              <WholeInput value={st.newPerDay} onCommit={(n) => smart.setNewPerDay(n)} />
            </div>
            <div className="grid gap-2">
              <Label>{s.reviewsPerDay}</Label>
              <WholeInput value={st.reviewsPerDay} onCommit={(n) => smart.setReviewsPerDay(n)} />
            </div>
          </div>
        </div>
      </SheetContent>
    </Sheet>
  );
}

/** A smart deck: what it offers today, its settings, and its rules, changed here above the notes they find. */
export function SmartDeck() {
  const { id = "" } = useParams();
  const goUp = useUp();
  const [st, smart] = useScreen(() => app.smartDeck(id), [id]);
  const notes = usePart(smart?.notes ?? null);
  const sheet = useOverlay("sheet");
  if (!st) return <Loading />;
  if (st.gone) return <Empty title={s.notFound}>{s.error("not_found")}</Empty>;
  const d = st.item;
  const due = d.newCount + d.learning + d.review;
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
      <Section title={s.smartRules}>
        {notes ? (
          <NoteList
            st={notes}
            browse={smart.notes}
            bar={
              <FilterBar filter={smart.notes.filter}>
                <SortMenu st={notes} browse={smart.notes} />
              </FilterBar>
            }
          />
        ) : (
          <Loading />
        )}
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
