import { Lock, Minus, Play, Plus, Search } from "lucide-react";
import { Link, useParams } from "react-router";
import { DeckMenu } from "@/components/decks";
import { DeckLook, Empty, Header, Loading, Ring, Section } from "@/components/kit";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { app, s, useScreen, type DeckScreen, type DeckState } from "@/rondo";
import { useOverlay, useUp } from "@/routing";

/** A sub-deck: what's left to study or what it waits for, and, in levels, what opens it. */
function SubDeck({ st, deck, i }: { st: DeckState; deck: DeckScreen; i: number }) {
  const d = st.children[i]!;
  const at = st.unlockAt[i] ?? 100;
  const levels = st.gated && i > 0;
  return (
    <div className="flex items-center gap-3 px-4 py-3">
      <Ring value={d.ring} locked={d.locked} size={32} />
      <DeckLook icon={d.icon} color={d.color} className="text-lg" />
      <Link to={`/decks/${d.id}`} className="min-w-0 flex-1">
        <div className="truncate">{d.name}</div>
        <div className="truncate text-sm text-muted-foreground">
          {levels ? `${s.unlockAt} ${s.unlockAfter(at, st.children[i - 1]!.name)} · ` : ""}
          {d.detail}
        </div>
      </Link>
      {levels && st.canEdit && (
        <div className="hidden items-center sm:flex">
          <Button
            size="icon-xs"
            variant="ghost"
            aria-label={s.lower}
            disabled={at <= 50}
            onClick={() => deck.setUnlockAt(d.id, at - 10)}
          >
            <Minus />
          </Button>
          <span className="w-10 text-center text-sm tabular-nums">{at}%</span>
          <Button
            size="icon-xs"
            variant="ghost"
            aria-label={s.raise}
            disabled={at >= 100}
            onClick={() => deck.setUnlockAt(d.id, at + 10)}
          >
            <Plus />
          </Button>
        </div>
      )}
      {d.locked && (
        <Button size="sm" variant="ghost" onClick={() => deck.unlock(d.id)}>
          <Lock /> {s.unlockNow}
        </Button>
      )}
      <DeckMenu d={d} />
    </div>
  );
}

/** The deck's first notes, each opening the editor. */
function Notes({ id }: { id: string }) {
  const [st] = useScreen(() => app.browse(`deck=${id}`), [id]);
  const note = useOverlay("note");
  if (!st || st.notes.length === 0) return null;
  const shown = st.notes.slice(0, 20);
  return (
    <Section
      title={s.notesCount}
      action={
        <Button variant="ghost" size="sm" asChild>
          <Link to={`/browse?deck=${id}`}>
            <Search /> {s.browse}
          </Link>
        </Button>
      }
    >
      <div className="divide-y overflow-hidden rounded-lg bg-card shadow-sm">
        {shown.map((n) => (
          <button
            key={n.id}
            type="button"
            className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-accent/50"
            onClick={() => note.open(n.id)}
          >
            <span className="min-w-0 flex-1 truncate">{n.text}</span>
            <span className="text-xs text-muted-foreground">{n.template}</span>
          </button>
        ))}
      </div>
      {st.notes.length > shown.length && (
        <Button variant="ghost" className="mt-2" asChild>
          <Link to={`/browse?deck=${id}`}>{s.showMore}</Link>
        </Button>
      )}
    </Section>
  );
}

export function Deck() {
  const { id = "" } = useParams();
  const goUp = useUp();
  const [st, deck] = useScreen(() => app.deck(id), [id]);
  const note = useOverlay("note");
  const created = useOverlay("new");
  if (!st) return <Loading />;
  const d = st.deck;
  if (!d) return <Empty title={s.notFound}>{s.error("not_found")}</Empty>;
  const up = d.parentId && st.path.length > 1 ? `/decks/${d.parentId}` : "/";
  const due = d.newCount + d.learning + d.review;
  return (
    <>
      <Header
        up={up}
        title={
          <span className="flex items-center gap-3">
            {d.icon && <span>{d.icon}</span>}
            <span className="truncate">{d.name}</span>
          </span>
        }
        sub={st.path.length > 1 ? st.path.slice(0, -1).join(" › ") : d.level}
      >
        <DeckMenu d={d} actions={st.menu} onGone={() => goUp(up)} className="size-9" />
      </Header>
      {st.frozen && <p className="mb-4 rounded-lg bg-warning px-4 py-3 text-sm">{s.removedByAuthor}</p>}
      {st.description && <p className="-mt-4 mb-6 whitespace-pre-line text-subtle">{st.description}</p>}
      {!st.canEdit && !st.frozen && <p className="-mt-2 mb-6 text-sm text-muted-foreground">{s.readOnlyDeck}</p>}
      <div className="mb-10 flex flex-col gap-2 sm:flex-row">
        {st.notes > 0 &&
          (due > 0 && !d.locked ? (
            <Button size="lg" className="sm:flex-1" asChild>
              <Link to={`/decks/${id}/study`}>
                <Play /> {s.studyN(due)}
              </Link>
            </Button>
          ) : (
            <p className="flex h-10 items-center text-sm text-subtle sm:flex-1">{s.nothingToStudy}</p>
          ))}
        {st.notes > 0 && (
          <Button size="lg" variant="outline" className="sm:flex-1" asChild>
            <Link to={`/browse?deck=${id}`}>
              <Search /> {s.viewNotes}
            </Link>
          </Button>
        )}
        {st.canEdit && (
          <Button
            size="lg"
            variant={st.notes > 0 ? "outline" : "default"}
            className="sm:flex-1"
            onClick={() => note.open("new", { in: id })}
          >
            <Plus /> {st.notes > 0 ? s.addNotes : s.createFirstNote}
          </Button>
        )}
      </div>
      {(st.children.length > 0 || st.canEdit) && (
        <Section
          title={st.gated ? s.levels : s.subdecks}
          action={
            st.canEdit && (
              <Button variant="ghost" size="sm" onClick={() => created.open("deck", { parent: id })}>
                <Plus /> {s.newShort}
              </Button>
            )
          }
        >
          {st.children.length > 0 && (
            <div className="divide-y overflow-hidden rounded-lg bg-card shadow-sm">
              {st.children.map((c, i) => (
                <SubDeck key={c.id} st={st} deck={deck} i={i} />
              ))}
              {st.canEdit && (st.children.length > 1 || st.gated) && (
                <label className="flex items-center justify-between gap-4 px-4 py-3">
                  <span>
                    <span className="block">{s.learnInOrder}</span>
                    <span className="text-sm text-muted-foreground">{s.levelsHint}</span>
                  </span>
                  <Switch checked={st.gated} onCheckedChange={(gated) => deck.setGated(gated)} />
                </label>
              )}
            </div>
          )}
        </Section>
      )}
      <Notes id={id} />
    </>
  );
}
