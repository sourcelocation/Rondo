import { Flag, MoreHorizontal, Pencil, Undo2, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useParams, useSearchParams } from "react-router";
import { readFilters } from "@/screens/Browse";
import { toast } from "sonner";
import { CardSurface, Side, speak, speechOf } from "@/components/Card";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Kbd } from "@/components/ui/kbd";
import { Progress } from "@/components/ui/progress";
import { app, s, useApp, useScreen } from "@/rondo";
import { useOverlay, useUp } from "@/routing";
import { cn } from "cn";

/** Each answer's colours, by rating (1 Again to 4 Easy): here, and in Settings' preview of them. */
export const GRADE = [
  "",
  "text-again border-again/40",
  "text-hard border-hard/40",
  "text-good border-good/40",
  "text-easy border-easy/40",
];

/** Whether a key press belongs to something else on the page: a text box, an open menu or dialog. */
const elsewhere = (e: KeyboardEvent) =>
  e.repeat ||
  e.metaKey ||
  e.ctrlKey ||
  e.altKey ||
  (e.target instanceof HTMLElement &&
    !!e.target.closest("input, textarea, [contenteditable=true], [role=menu], [role=dialog], [role=alertdialog]"));

/** What a study session opens on, and where leaving it goes. */
function useSession(source: "deck" | "smart" | "these") {
  const { id } = useParams();
  const [params] = useSearchParams();
  if (source === "smart") return { make: () => app.studySmart(id!), key: id, up: `/smart/${id}` };
  if (source === "these") {
    const f = readFilters(params);
    const up = `/browse?${params}`;
    return { make: () => app.studyThese(f.q, f.deck, f.type, f.tags, f.state, f.marked), key: up, up };
  }
  return { make: () => app.study(id ?? null), key: id, up: id ? `/decks/${id}` : "/" };
}

/**
 * Studying a deck, a smart deck ([source] smart) or what Browse found ([source] these): the card,
 * then the answer and the buttons. Space or Enter shows the answer (then answers Good), 1–4 answer
 * by button, Z undoes the last answer, E edits the card, Esc leaves.
 */
export function Study({ source = "deck" }: { source?: "deck" | "smart" | "these" }) {
  const [params] = useSearchParams();
  const session = useSession(source);
  const [st, study] = useScreen(session.make, [session.key]);
  const settings = useApp();
  const note = useOverlay("note");
  const goUp = useUp();
  const [typed, setTyped] = useState("");
  const leave = () => goUp(session.up);
  const editing = params.has("note");

  const reveal = () => st && !st.back && study.reveal(typed);
  const answer = (rating: number) => {
    study.answer(rating);
    setTyped("");
  };

  // Back from editing the card in place: show what changed.
  const wasEditing = useRef(false);
  useEffect(() => {
    if (wasEditing.current && !editing) study.refresh();
    wasEditing.current = editing;
  }, [editing, study]);

  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (!st || editing || elsewhere(e)) return;
      if (e.key === "Escape") leave();
      else if ((e.key === "z" || e.key === "Z") && st.canUndo) study.undo();
      else if ((e.key === "e" || e.key === "E") && st.noteId && st.canEdit) note.open(st.noteId);
      else if (!st.back && (e.key === " " || e.key === "Enter")) reveal();
      else if (st.back && (e.key === " " || e.key === "Enter")) answer(3);
      else if (st.back && /^[1-4]$/.test(e.key) && st.answers[+e.key - 1]) answer(st.answers[+e.key - 1]!.rating);
      else return;
      e.preventDefault();
    };
    addEventListener("keydown", key);
    return () => removeEventListener("keydown", key);
  });

  // Fields in the deck's language are read as they first show: the question, then what the answer adds.
  const front = st?.front;
  const back = st?.back;
  useEffect(() => {
    if (st?.language && front && !back) speak(speechOf(front), st.language);
  }, [front, back, st?.language]);
  useEffect(() => {
    if (!st?.language || !back) return;
    const asked = new Set(speechOf(front));
    speak(
      speechOf(back).filter((t) => !asked.has(t)),
      st.language,
    );
  }, [back]); // eslint-disable-line react-hooks/exhaustive-deps

  const opened = st?.opened.join();
  useEffect(
    () =>
      opened
        ?.split(",")
        .filter(Boolean)
        .forEach((name) => toast(s.opened(name))),
    [opened],
  );

  if (!st) return null;
  const total = st.done + st.left;
  return (
    <div className="flex min-h-dvh flex-col bg-background">
      <header className="grid grid-cols-[1fr_auto_1fr] items-center gap-2 px-3 py-3">
        <div className="flex items-center gap-1">
          <Button variant="ghost" size="icon" aria-label={s.close} onClick={leave}>
            <X />
          </Button>
          <span className="hidden min-w-0 truncate text-sm text-muted-foreground sm:block">{st.deck}</span>
        </div>
        {!st.finished ? (
          <div className="flex w-40 flex-col items-center gap-1">
            <span className="text-xs text-muted-foreground tabular-nums">{s.left(st.left)}</span>
            <Progress value={total ? (100 * st.done) / total : 0} aria-label={s.progress} className="h-1" />
          </div>
        ) : (
          <span />
        )}
        <div className="flex items-center justify-end gap-1">
          {st.canUndo && (
            <Button variant="ghost" size="sm" onClick={() => study.undo()} title={`${s.undo} (Z)`}>
              <Undo2 /> <span className="hidden sm:inline">{s.undo}</span>
            </Button>
          )}
          {st.noteId && st.canEdit && (
            <Button
              variant="ghost"
              size="icon"
              aria-label={s.editCard}
              title={`${s.editCard} (E)`}
              onClick={() => note.open(st.noteId!)}
            >
              <Pencil />
            </Button>
          )}
          {st.noteId && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="icon" aria-label={s.more}>
                  <MoreHorizontal />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="min-w-52">
                <DropdownMenuItem onSelect={() => study.mark(!st.marked)}>
                  <Flag /> {st.marked ? s.unmark : s.mark}
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem onSelect={() => study.bury()}>{s.bury}</DropdownMenuItem>
                <DropdownMenuItem onSelect={() => study.suspendNote()}>{s.suspend}</DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          )}
        </div>
      </header>

      <main className="mx-auto flex w-full max-w-[var(--rd-size-content-max)] flex-1 flex-col justify-center px-4 py-6">
        {st.finished ? (
          <div className="flex flex-col items-center gap-8 text-center">
            <h1>{st.done ? s.nicelyDone : s.finished}</h1>
            {st.done > 0 ? (
              <div className="rounded-lg bg-card px-8 py-4 shadow-sm">
                <div className="rd-text-numeric-lg">{st.done.toLocaleString()}</div>
                <div className="text-xs text-muted-foreground">{s.answerWord(st.done)}</div>
              </div>
            ) : (
              <p className="text-subtle">{s.finishedHint}</p>
            )}
            {st.ahead > 0 && <p className="max-w-sm text-sm text-subtle">{s.aheadHint(st.ahead)}</p>}
            <div className="flex flex-wrap justify-center gap-2">
              {st.ahead > 0 && (
                <Button size="lg" variant="outline" onClick={() => study.goAhead()}>
                  {s.goAhead}
                </Button>
              )}
              <Button size="lg" onClick={leave}>
                {s.done}
              </Button>
            </div>
          </div>
        ) : (
          <CardSurface
            onClick={!st.back ? reveal : undefined}
            className={cn(!st.back && "cursor-pointer")}
            style={{ zoom: (settings?.textSize ?? 100) / 100 }}
          >
            {st.marked && (
              <Flag className="absolute top-5 right-5 size-4 text-[var(--rd-color-flag-red)]" aria-label={s.marked} />
            )}
            <div
              key={`${st.noteId}:${!!st.back}`}
              className="animate-in fade-in duration-200 motion-reduce:animate-none"
            >
              {st.back ? (
                <Side side={st.back} typed={st.typed} correct={st.correct} language={st.language} autoPlay />
              ) : (
                st.front && (
                  <div onClick={(e) => e.target instanceof HTMLInputElement && e.stopPropagation()}>
                    <Side
                      side={st.front}
                      typed={typed}
                      onType={setTyped}
                      onDone={reveal}
                      language={st.language}
                      autoPlay
                    />
                  </div>
                )
              )}
            </div>
          </CardSurface>
        )}
      </main>

      {!st.finished && (
        <footer className="sticky bottom-0 mx-auto w-full max-w-[var(--rd-size-content-max)] px-4 pt-2 pb-[max(1rem,env(safe-area-inset-bottom))]">
          {st.back ? (
            <div className="grid gap-2" style={{ gridTemplateColumns: `repeat(${st.answers.length}, minmax(0, 1fr))` }}>
              {st.answers.map((a, i) => (
                <button
                  key={a.rating}
                  type="button"
                  onClick={() => answer(a.rating)}
                  className={cn(
                    "flex min-h-16 flex-col items-center justify-center gap-0.5 rounded-xl border bg-card py-3 hover:bg-accent/50",
                    GRADE[a.rating],
                  )}
                >
                  <span className="font-medium">{a.label}</span>
                  <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
                    {a.interval}
                    <Kbd className="hidden sm:inline-flex">{i + 1}</Kbd>
                  </span>
                </button>
              ))}
            </div>
          ) : (
            <Button size="lg" className="w-full rounded-xl py-6" onClick={reveal}>
              {s.showAnswer}
              <Kbd className="hidden bg-primary-foreground/15 text-primary-foreground sm:inline-flex">{s.space}</Kbd>
            </Button>
          )}
        </footer>
      )}
    </div>
  );
}
