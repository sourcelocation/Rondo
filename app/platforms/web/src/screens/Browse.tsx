import { ArrowDownUp, Bookmark, Check, Flag, Play, Search, X } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import { Link, useNavigate } from "react-router";
import { ask, askTags, confirm, Empty, Header, Loading, pick } from "@/components/kit";
import { TagFilter } from "@/components/tags";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuTrigger,
} from "@/components/ui/context-menu";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Toggle } from "@/components/ui/toggle";
import {
  app,
  date,
  s,
  usePart,
  useScreen,
  type BrowseScreen,
  type BrowseState,
  type Choice,
  type FilterScreen,
  type NoteItem,
} from "@/rondo";
import { useOverlay, usePageAddress } from "@/routing";
import { cn } from "cn";

const STATE: Record<string, string> = {
  new: "text-new",
  learning: "text-learning",
  review: "text-review",
  suspended: "text-paused",
};

function Filter({
  value,
  any,
  options,
  onChange,
}: {
  value: string | null;
  any: string;
  options: Choice[];
  onChange: (v: string | null) => void;
}) {
  return (
    <Select value={value ?? "*"} onValueChange={(v) => onChange(v === "*" ? null : v)}>
      <SelectTrigger size="sm" className="max-w-56">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value="*">{any}</SelectItem>
        {options.map((o) => (
          <SelectItem key={o.id} value={o.id}>
            {o.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

/** What a note's state shows: when it's due, or that it's new or suspended. */
const stateText = (n: NoteItem) =>
  n.due && n.state !== "new" && n.state !== "suspended"
    ? date(n.due)
    : ({ new: s.stateNew, suspended: s.stateSuspended, learning: s.stateLearning } as Record<string, string>)[n.state];

/** A filter's pickers: text, deck, note type, card state, tags and mark; [children] at the end of the row. */
export function FilterBar({ filter, children }: { filter: FilterScreen; children?: ReactNode }) {
  const f = usePart(filter);
  const [text, setText] = useState<string | null>(null);
  useEffect(() => {
    if (text == null || !f || text === f.text) return;
    const timer = setTimeout(() => filter.setText(text), 250);
    return () => clearTimeout(timer);
  }, [text]); // eslint-disable-line react-hooks/exhaustive-deps
  if (!f) return null;
  return (
    <>
      <div className="relative">
        <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          type="search"
          aria-label={s.search}
          placeholder={s.search}
          value={text ?? f.text}
          onChange={(e) => setText(e.target.value)}
          className="pl-9"
        />
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <Filter value={f.deckId ?? null} any={s.allDecks} options={f.decks} onChange={(v) => filter.setDeck(v)} />
        <Filter
          value={f.templateId ?? null}
          any={s.allTypes}
          options={f.templates}
          onChange={(v) => filter.setTemplate(v)}
        />
        <Filter
          value={f.cardState ?? null}
          any={s.anyState}
          onChange={(v) => filter.setCardState(v)}
          options={[
            ["new", s.stateNew],
            ["learning", s.stateLearning],
            ["review", s.stateReview],
            ["suspended", s.stateSuspended],
          ].map(([id, label]) => ({ id, label }) as Choice)}
        />
        <TagFilter value={f.tags} onChange={(tags) => filter.setTags(tags)} />
        <Toggle size="sm" variant="outline" pressed={f.marked} onPressedChange={(on) => filter.setMarked(on)}>
          <Flag /> {s.marked}
        </Toggle>
        <span className="flex-1" />
        {children}
      </div>
    </>
  );
}

/** How the notes are sorted, and changing it. */
export function SortMenu({ st, browse }: { st: BrowseState; browse: BrowseScreen }) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="sm" aria-label={s.sort}>
          <ArrowDownUp /> {s.sortBy(st.sort)}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {st.sorts.map((o) => (
          <DropdownMenuItem key={o.name} onSelect={() => browse.sortBy(o)}>
            {s.sortBy(o)}
            {o === st.sort && <Check className="ms-auto" />}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/**
 * The notes [browse] finds; select some to change them together. [bar] sits above them, sticky,
 * while nothing is selected: the filter and what to do with what it finds.
 */
export function NoteList({ st, browse, bar }: { st: BrowseState; browse: BrowseScreen; bar: ReactNode }) {
  const note = useOverlay("note");
  const [picked, setPicked] = useState<Set<string>>(new Set());
  // A new filter finds other notes: what was selected goes.
  useEffect(() => setPicked(new Set()), [st.address]);
  const ids = [...picked];
  const toggle = (id: string, on: boolean) => {
    const next = new Set(picked);
    if (on) next.add(id);
    else next.delete(id);
    setPicked(next);
  };
  return (
    <>
      <div className="sticky top-14 z-10 -mx-4 mb-4 grid gap-3 bg-background/95 px-4 py-3 backdrop-blur sm:-mx-8 sm:px-8 md:top-0">
        {ids.length > 0 ? (
          <Selection ids={ids} browse={browse} decks={st.decks} all={st.notes} onSelect={setPicked} />
        ) : (
          bar
        )}
      </div>
      {st.notes.length === 0 ? (
        <Empty icon={Search}>{s.noNotes}</Empty>
      ) : (
        <div className="divide-y">
          {st.notes.map((n) => (
            <ContextMenu key={n.id}>
              <ContextMenuTrigger asChild>
                <div className={cn("flex items-center gap-3 py-2", picked.has(n.id) && "bg-accent/50")}>
                  <Checkbox
                    aria-label={s.select}
                    checked={picked.has(n.id)}
                    onCheckedChange={(on) => toggle(n.id, on === true)}
                  />
                  <button
                    type="button"
                    className="min-w-0 flex-1 text-left"
                    onClick={() => (ids.length ? toggle(n.id, !picked.has(n.id)) : note.open(n.id))}
                  >
                    <div className="truncate">{n.text}</div>
                    <div className="truncate text-xs text-muted-foreground">
                      {n.deck} · {n.template}
                      {n.tags.length > 0 && ` · ${n.tags.join(" ")}`}
                    </div>
                  </button>
                  {n.marked && <Flag className="size-4 text-[var(--rd-color-flag-red)]" aria-label={s.marked} />}
                  <span className={cn("w-24 text-right text-xs", STATE[n.state])}>{stateText(n)}</span>
                </div>
              </ContextMenuTrigger>
              <ContextMenuContent>
                <ContextMenuItem onSelect={() => note.open(n.id)}>{s.open}</ContextMenuItem>
                <ContextMenuItem onSelect={() => toggle(n.id, !picked.has(n.id))}>
                  {picked.has(n.id) ? s.deselect : s.select}
                </ContextMenuItem>
                <ContextMenuSeparator />
                <ContextMenuItem onSelect={() => browse.mark([n.id], !n.marked)}>
                  {n.marked ? s.unmark : s.mark}
                </ContextMenuItem>
                <ContextMenuItem onSelect={() => browse.suspend([n.id], n.state !== "suspended")}>
                  {n.state === "suspended" ? s.unsuspend : s.suspend}
                </ContextMenuItem>
                <ContextMenuItem
                  onSelect={async () => {
                    const to = await pick(s.moveTo, st.decks);
                    if (to) browse.move([n.id], to);
                  }}
                >
                  {s.moveTo}
                </ContextMenuItem>
                <ContextMenuSeparator />
                <ContextMenuItem
                  variant="destructive"
                  onSelect={async () => (await confirm(`${s.delete}?`, s.deleteNoteHint)) && browse.delete([n.id])}
                >
                  {s.delete}
                </ContextMenuItem>
              </ContextMenuContent>
            </ContextMenu>
          ))}
        </div>
      )}
      {st.more && (
        <Button variant="ghost" className="mt-4 w-full" onClick={() => browse.more()}>
          {s.showMore}
        </Button>
      )}
    </>
  );
}

/**
 * Browse: the notes a filter finds, studied as they are or kept as a smart deck. The filter and
 * sort live in the address, so Back, a reload or a shared link brings them back.
 */
export function Browse() {
  const [address, setAddress] = usePageAddress();
  const navigate = useNavigate();
  const [st, browse] = useScreen(() => app.browse(address), []);
  const filter = usePart(browse?.filter ?? null);
  // The address says what to show when it changes from outside (Back, a link), and shows what's shown.
  useEffect(() => void browse?.open(address), [address]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => void (st && setAddress(st.address)), [st?.address]); // eslint-disable-line react-hooks/exhaustive-deps
  if (!st || !filter) return <Loading />;
  const due = st.due + st.newCount;
  return (
    <>
      <Header title={s.browse} />
      <NoteList
        st={st}
        browse={browse}
        bar={
          <>
            <FilterBar filter={browse.filter}>
              <SortMenu st={st} browse={browse} />
            </FilterBar>
            {st.notes.length > 0 && (
              <div className="flex flex-wrap items-center gap-2">
                {/* With nothing due it still opens: reviews not due yet can come early. */}
                <Button size="sm" variant={due ? "secondary" : "ghost"} asChild>
                  <Link to={`/browse/study?${filter.address}`}>
                    <Play /> {s.studyThese}
                    <span className="text-muted-foreground tabular-nums">
                      {s.studyTheseCounts(st.due, st.newCount)}
                    </span>
                  </Link>
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={async () => {
                    const name = await ask(s.smartDeckName);
                    if (name) browse.keep(name, (id) => navigate(`/smart/${id}`));
                  }}
                >
                  <Bookmark /> {s.keepAsSmartDeck}
                </Button>
              </div>
            )}
          </>
        }
      />
    </>
  );
}

/** What can be done to the selected notes. */
function Selection({
  ids,
  browse,
  decks,
  all,
  onSelect,
}: {
  ids: string[];
  browse: BrowseScreen;
  decks: Choice[];
  all: NoteItem[];
  onSelect: (ids: Set<string>) => void;
}) {
  return (
    <div className="flex flex-wrap items-center gap-1 rounded-lg bg-card p-1.5 shadow-sm">
      <Button variant="ghost" size="icon-sm" aria-label={s.clear} onClick={() => onSelect(new Set())}>
        <X />
      </Button>
      <span className="px-1 text-sm font-medium">{s.selected(ids.length)}</span>
      <Button variant="ghost" size="sm" onClick={() => onSelect(new Set(all.map((n) => n.id)))}>
        {s.selectAll}
      </Button>
      <span className="flex-1" />
      <Button
        size="sm"
        variant="ghost"
        onClick={async () => {
          const to = await pick(s.moveTo, decks);
          if (to) browse.move(ids, to);
        }}
      >
        {s.moveTo}
      </Button>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button size="sm">{s.edit}…</Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="min-w-48">
          <DropdownMenuItem
            onSelect={async () => {
              const tags = await askTags(s.addTags, s.add);
              if (tags) browse.tag(ids, tags, []);
            }}
          >
            {s.addTags}
          </DropdownMenuItem>
          <DropdownMenuItem
            onSelect={async () => {
              const tags = await askTags(s.removeTags, s.remove);
              if (tags) browse.tag(ids, [], tags);
            }}
          >
            {s.removeTags}
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem onSelect={() => browse.mark(ids, true)}>{s.mark}</DropdownMenuItem>
          <DropdownMenuItem onSelect={() => browse.mark(ids, false)}>{s.unmark}</DropdownMenuItem>
          <DropdownMenuItem onSelect={() => browse.suspend(ids, true)}>{s.suspend}</DropdownMenuItem>
          <DropdownMenuItem onSelect={() => browse.suspend(ids, false)}>{s.unsuspend}</DropdownMenuItem>
          <DropdownMenuItem
            onSelect={async () => (await confirm(`${s.reset}?`, s.resetHint, s.reset)) && browse.reset(ids)}
          >
            {s.reset}
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            variant="destructive"
            onSelect={async () =>
              (await confirm(`${s.delete} ${s.selected(ids.length)}?`, s.deleteNoteHint)) &&
              (browse.delete(ids), onSelect(new Set()))
            }
          >
            {s.delete}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}
