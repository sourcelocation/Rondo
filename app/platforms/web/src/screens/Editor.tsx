import { ChevronDown, Trash2 } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router";
import { CardSurface, Side } from "@/components/Card";
import { AudioField, ImageField, OcclusionField } from "@/components/MediaField";
import { RichText } from "@/components/RichText";
import { confirm, Empty, Header, Loading, Spinner } from "@/components/kit";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Kbd } from "@/components/ui/kbd";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { app, s, useScreen, type EditorScreen, type EditorState, type FieldValue } from "@/rondo";
import { useOverlay, useUp } from "@/routing";
import { TemplatePicker } from "@/screens/Templates";

/** One field, written where it shows on the card. */
function FieldInput({ f, st, ed, first }: { f: FieldValue; st: EditorState; ed: EditorScreen; first: boolean }) {
  const disabled = st.readOnly;
  if (f.kind === "image")
    return (
      <ImageField
        label={f.name}
        value={f.value}
        onBytes={(b, m) => ed.attach(f.id, b, m)}
        onClear={() => ed.clear(f.id)}
      />
    );
  if (f.kind === "audio")
    return (
      <AudioField
        label={f.name}
        value={f.value}
        onBytes={(b, m) => ed.attach(f.id, b, m)}
        onClear={() => ed.clear(f.id)}
      />
    );
  if (f.kind === "occlusion")
    return (
      <OcclusionField
        label={f.name}
        image={f.image ?? null}
        masks={f.masks}
        onBytes={(b, m) => ed.attach(f.id, b, m)}
        onMasks={(flat) => ed.setMasks(f.id, flat)}
        onClear={() => ed.clear(f.id)}
      />
    );
  return (
    <RichText
      value={f.value}
      cloze={f.each}
      placeholder={f.name}
      size={f.size}
      lang={f.foreign ? st.language : null}
      autoFocus={first && !st.noteId && !disabled}
      disabled={disabled}
      onChange={(v) => ed.set(f.id, v)}
    />
  );
}

/** The cards a note makes, front and back, as they'll show. */
function CardsMade({ st }: { st: EditorState }) {
  const [open, setOpen] = useState(false);
  if (st.cards.length < 2) return null;
  return (
    <p className="text-muted-foreground">
      {s.makes(st.cards.length)}{" "}
      <Button variant="link" size="sm" className="h-auto p-0" onClick={() => setOpen(true)}>
        {s.seeThem}
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-xl">
          <DialogHeader>
            <DialogTitle>{s.cardsItMakes}</DialogTitle>
            <DialogDescription className="sr-only">{s.cardsItMakes}</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4">
            {st.cards.map((c, i) => (
              <CardSurface key={i} small className="grid gap-4 sm:grid-cols-2">
                <Side side={c.front} language={st.language} />
                <Side side={c.back} language={st.language} />
              </CardSurface>
            ))}
          </div>
        </DialogContent>
      </Dialog>
    </p>
  );
}

/**
 * Writing notes on the card itself: the fields where they show, the type and deck above, what's
 * missing and what it makes below. A new note keeps the editor open for the next; ⌘↵ saves.
 * [onDone]: after saving an existing note or deleting one.
 */
export function NoteEditor({ id, deck, onDone }: { id: string | null; deck: string | null; onDone: () => void }) {
  const [st, ed] = useScreen(() => app.editor(id, deck), [id, deck]);
  const [picking, setPicking] = useState(false);
  const [tried, setTried] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  // A new note starts in its first field, and so does the next one after Add.
  const ready = !!st;
  useEffect(() => {
    if (!ready || id) return;
    const timer = setTimeout(() => box.current?.querySelector<HTMLElement>(".tiptap")?.focus());
    return () => clearTimeout(timer);
  }, [ready, st?.added, st?.templateId, id]);
  const save = () => {
    if (!st?.canSave) return setTried(true);
    setTried(false);
    ed.save(() => id && onDone());
  };
  useEffect(() => {
    const key = (e: KeyboardEvent) => e.key === "Enter" && (e.metaKey || e.ctrlKey) && (e.preventDefault(), save());
    addEventListener("keydown", key);
    return () => removeEventListener("keydown", key);
  });
  if (!st) return <Loading />;
  if (st.gone) return <Empty title={s.notFound}>{s.error("not_found")}</Empty>;
  const front = st.fields.filter((f) => f.side === "front");
  const back = st.fields.filter((f) => f.side === "back");
  return (
    <div ref={box} className="grid gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Select value={st.deckId ?? undefined} onValueChange={(d) => ed.setDeck(d)} disabled={st.readOnly}>
          <SelectTrigger size="sm" className="max-w-64 border-0 shadow-none" aria-label={s.deck}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {st.decks.map((d) => (
              <SelectItem key={d.id} value={d.id}>
                {d.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Button variant="ghost" size="sm" disabled={st.readOnly || !!id} onClick={() => setPicking(true)}>
          {st.templateName} <ChevronDown />
        </Button>
      </div>
      {st.anki ? (
        <p className="rounded-lg bg-warning px-4 py-3 text-sm">{s.ankiReadOnly}</p>
      ) : (
        <CardSurface key={`${st.templateId}:${st.added}`} className="items-stretch gap-5">
          {[
            { label: s.front, fields: front },
            { label: s.backSide, fields: back },
          ].map(
            (side, i) =>
              side.fields.length > 0 && (
                <section key={side.label} className={i ? "grid gap-5 border-t pt-5" : "grid gap-5"}>
                  <div className="text-xs font-medium tracking-wider text-muted-foreground uppercase">{side.label}</div>
                  {side.fields.map((f) => (
                    <FieldInput key={f.id} f={f} st={st} ed={ed} first={f === st.fields[0]} />
                  ))}
                </section>
              ),
          )}
        </CardSurface>
      )}
      <div className="grid gap-1 px-1 text-sm">
        {st.problem && (tried || st.fields.some((f) => f.value)) && (
          <p role="alert" className="text-destructive">
            {st.problem}
          </p>
        )}
        {st.canUndoAdd && (
          <p role="status" className="text-subtle">
            {s.added}.{" "}
            <Button variant="link" size="sm" className="h-auto p-0" onClick={() => ed.undoAdd()}>
              {s.undo}
            </Button>
          </p>
        )}
        <CardsMade st={st} />
      </div>
      {!st.readOnly && (
        <div className="flex items-center justify-end gap-2">
          {id && (
            <Button
              variant="ghost"
              size="icon"
              aria-label={s.delete}
              onClick={async () => (await confirm(`${s.delete}?`, s.deleteNoteHint)) && ed.delete(onDone)}
            >
              <Trash2 />
            </Button>
          )}
          <Button size="lg" onClick={save}>
            {id ? s.save : s.add}
            <Kbd className="hidden bg-primary-foreground/15 text-primary-foreground sm:inline-flex">⌘↵</Kbd>
          </Button>
        </div>
      )}
      <TemplatePicker
        open={picking}
        current={st.templateId}
        onClose={() => setPicking(false)}
        onPick={(t) => (ed.setTemplate(t), setPicking(false))}
      />
    </div>
  );
}

/** The editor over whatever page opened it: `?note=<id>` or `?note=new&in=<deck>`. */
export function NoteOverlay() {
  const note = useOverlay("note");
  const [params] = useSearchParams();
  const value = note.value;
  const deck = params.get("in");
  return (
    <Dialog open={!!value} onOpenChange={(o) => !o && note.close()}>
      <DialogContent
        onOpenAutoFocus={(e) => value === "new" && e.preventDefault()}
        className="max-h-dvh overflow-y-auto max-sm:h-dvh max-sm:max-w-none max-sm:rounded-none sm:max-h-[92dvh] sm:max-w-2xl"
      >
        <DialogHeader>
          <DialogTitle>{value === "new" ? s.newNote : s.editNote}</DialogTitle>
          <DialogDescription className="sr-only">{value === "new" ? s.newNote : s.editNote}</DialogDescription>
        </DialogHeader>
        {value && <NoteEditor id={value === "new" ? null : value} deck={deck} onDone={note.close} />}
      </DialogContent>
    </Dialog>
  );
}

/** A note as a page of its own, for links straight to it. */
export function NotePage() {
  const { id = "" } = useParams();
  const goUp = useUp();
  const [st] = useScreen(() => app.editor(id, null), [id]);
  const up = st?.deckId ? `/decks/${st.deckId}` : "/";
  return (
    <>
      <Header up={up} title={s.editNote} sub={st?.deckName && <Link to={up}>{st.deckName}</Link>} />
      {st ? <NoteEditor id={id} deck={null} onDone={() => goUp(up)} /> : <Spinner />}
    </>
  );
}
