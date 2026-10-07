import { combine } from "@atlaskit/pragmatic-drag-and-drop/combine";
import { draggable, dropTargetForElements } from "@atlaskit/pragmatic-drag-and-drop/element/adapter";
import {
  attachInstruction,
  extractInstruction,
  type Instruction,
} from "@atlaskit/pragmatic-drag-and-drop-hitbox/tree-item";
import {
  ChevronRight,
  Copy,
  FolderInput,
  FolderPlus,
  LogOut,
  MoreHorizontal,
  Pencil,
  Play,
  Plus,
  Search,
  Settings2,
  Share2,
  Trash2,
  X,
  type LucideIcon,
} from "lucide-react";
import { createContext, use, useEffect, useRef, useState, type ReactNode } from "react";
import { Link, useLocation, useNavigate } from "react-router";
import { ask, confirm, DECK_COLORS, DeckLook, deckColor, pick, Ring } from "@/components/kit";
import { Button } from "@/components/ui/button";
import { Command, CommandEmpty, CommandInput, CommandItem, CommandList } from "@/components/ui/command";
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuTrigger,
} from "@/components/ui/context-menu";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { languageName, languages, s, type DeckItem, type Home, type HomeState } from "@/rondo";
import { useOverlay } from "@/routing";
import { cn } from "cn";

/** The one Home screen the shell keeps open: the deck trees, for the sidebar and the Home page. */
export const HomeContext = createContext<[HomeState | null, Home] | null>(null);
export const useHome = () => use(HomeContext)!;

const fine = matchMedia("(pointer: fine)").matches;

/** Moves a deck, asking first when it would leave a deck others borrow. */
export function move(home: Home, id: string, parent: string | null, before: string | null) {
  home.move(id, parent, before, false, async (question) => {
    if (await confirm(s.moveQuestion, question, s.move, false)) home.move(id, parent, before, true, () => {});
  });
}

/** Picks where to move a deck ("Move to…"), then moves it. */
export async function moveTo(home: Home, id: string) {
  const to = await pick(s.moveTo, home.targets(id));
  if (to != null) move(home, id, to || null, null);
}

interface Action {
  label: string;
  icon: LucideIcon;
  run: () => void;
  destructive?: boolean;
  separated?: boolean;
}

/** Everything a deck's menu offers, for who you are to it. */
function useActions(d: DeckItem): Action[] {
  const [, home] = useHome();
  const navigate = useNavigate();
  const note = useOverlay("note");
  const created = useOverlay("new");
  const own = d.role === "owner";
  const write = own || d.role === "editor";
  const out: (Action | false)[] = [
    d.notes > 0 && { label: s.study, icon: Play, run: () => navigate(`/decks/${d.id}/study`) },
    write && { label: s.deckSettings, icon: Settings2, run: () => navigate(`/decks/${d.id}?sheet=settings`) },
    own && { label: s.share, icon: Share2, run: () => navigate(`/decks/${d.id}?sheet=share`) },
    { label: s.browseDeck, icon: Search, run: () => navigate(`/browse?deck=${d.id}`) },
    write && { label: s.addNote, icon: Plus, run: () => note.open("new", { in: d.id }), separated: true },
    write && { label: s.newSubdeck, icon: FolderPlus, run: () => created.open("deck", { parent: d.id }) },
    write && {
      label: s.rename,
      icon: Pencil,
      run: async () => {
        const name = await ask(s.rename, d.name);
        if (name) home.rename(d.id, name);
      },
    },
    own && { label: s.moveTo, icon: FolderInput, run: () => moveTo(home, d.id) },
    { label: s.copy, icon: Copy, run: () => home.copy(d.id, (copy) => navigate(`/decks/${copy}`)) },
    own && {
      label: s.delete,
      icon: Trash2,
      destructive: true,
      separated: true,
      run: async () => (await confirm(`${s.delete} “${d.name}”?`, s.deleteDeckHint)) && home.delete(d.id),
    },
    !own &&
      d.depth === 0 && {
        label: s.leave,
        icon: LogOut,
        destructive: true,
        separated: true,
        run: async () => (await confirm(`${s.leave} “${d.name}”?`, s.leaveHint, s.leave)) && home.leave(d.id),
      },
  ];
  return out.filter((a): a is Action => !!a);
}

/** A deck's ⋯ menu. */
export function DeckMenu({ d, className }: { d: DeckItem; className?: string }) {
  const actions = useActions(d);
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon-sm" aria-label={`${s.more}: ${d.name}`} className={className}>
          <MoreHorizontal />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-48">
        {actions.map((a) => (
          <MenuEntry key={a.label} a={a} separator={DropdownMenuSeparator} item={DropdownMenuItem} />
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** The same menu on right-click. */
function DeckContextMenu({ d, children }: { d: DeckItem; children: ReactNode }) {
  const actions = useActions(d);
  return (
    <ContextMenu>
      <ContextMenuTrigger asChild>{children}</ContextMenuTrigger>
      <ContextMenuContent className="min-w-48">
        {actions.map((a) => (
          <MenuEntry key={a.label} a={a} separator={ContextMenuSeparator} item={ContextMenuItem} />
        ))}
      </ContextMenuContent>
    </ContextMenu>
  );
}

function MenuEntry({
  a,
  separator: Separator,
  item: Item,
}: {
  a: Action;
  separator: typeof DropdownMenuSeparator;
  item: typeof DropdownMenuItem;
}) {
  return (
    <>
      {a.separated && <Separator />}
      <Item variant={a.destructive ? "destructive" : "default"} onSelect={a.run}>
        <a.icon />
        {a.label}
      </Item>
    </>
  );
}

/**
 * Makes a row something to drag (desktop only, your own decks) and a place to drop decks: its top
 * and bottom edges put a deck before or after it, its middle puts the deck inside.
 */
function useDrag(
  ref: React.RefObject<HTMLElement | null>,
  d: DeckItem,
  items: DeckItem[],
  indent: number,
  open: boolean,
) {
  const [, home] = useHome();
  const [dragging, setDragging] = useState(false);
  const [hint, setHint] = useState<Instruction | null>(null);
  useEffect(() => {
    const element = ref.current;
    if (!element || !fine || d.role !== "owner") return;
    const siblings = items.filter((x) => x.parentId === d.parentId);
    return combine(
      draggable({
        element,
        getInitialData: () => ({ kind: "deck", id: d.id }),
        onDragStart: () => setDragging(true),
        onDrop: () => setDragging(false),
      }),
      dropTargetForElements({
        element,
        canDrop: ({ source }) => source.data.kind === "deck" && source.data.id !== d.id,
        getData: ({ input, source }) => {
          const allowed = new Set(home.targets(source.data.id as string).map((t) => t.id));
          const block: Instruction["type"][] = ["reparent"];
          if (!allowed.has(d.id)) block.push("make-child");
          if (!allowed.has(d.parentId ?? "")) block.push("reorder-above", "reorder-below");
          return attachInstruction(
            { id: d.id },
            {
              input,
              element,
              currentLevel: d.depth,
              indentPerLevel: indent,
              mode: d.children && open ? "expanded" : "standard",
              block,
            },
          );
        },
        onDrag: ({ self }) => setHint(extractInstruction(self.data)),
        onDragLeave: () => setHint(null),
        onDrop: ({ self, source }) => {
          setHint(null);
          const id = source.data.id as string;
          const how = extractInstruction(self.data)?.type;
          const next = siblings[siblings.indexOf(d) + 1]?.id ?? null;
          if (how === "make-child") move(home, id, d.id, null);
          if (how === "reorder-above") move(home, id, d.parentId ?? null, d.id);
          if (how === "reorder-below") move(home, id, d.parentId ?? null, next);
        },
      }),
    );
  }, [ref, d, items, indent, open, home]);
  return { dragging, hint };
}

/** Where a dragged deck will land: a line between decks, or a ring around the one it goes into. */
function DropHint({ hint }: { hint: Instruction | null }) {
  if (!hint || hint.type === "instruction-blocked" || hint.type === "reparent") return null;
  if (hint.type === "make-child")
    return <span aria-hidden className="pointer-events-none absolute inset-0 rounded-lg ring-2 ring-primary" />;
  return (
    <span
      aria-hidden
      className={cn(
        "pointer-events-none absolute inset-x-1 z-10 h-0.5 rounded-full bg-primary",
        hint.type === "reorder-above" ? "-top-px" : "-bottom-px",
      )}
    />
  );
}

/** Collapsed decks, remembered on this device and shared by every tree. */
const CLOSED = "rondo.closed";
function useClosed() {
  const [closed, setClosed] = useState<Set<string>>(() => {
    try {
      return new Set(JSON.parse(localStorage.getItem(CLOSED) ?? "[]"));
    } catch {
      return new Set();
    }
  });
  const toggle = (id: string) => {
    const next = new Set(closed);
    if (!next.delete(id)) next.add(id);
    setClosed(next);
    try {
      localStorage.setItem(CLOSED, JSON.stringify([...next]));
    } catch {
      /* private window: not remembered */
    }
  };
  return [closed, toggle] as const;
}

function Chevron({ d, open, toggle }: { d: DeckItem; open: boolean; toggle: () => void }) {
  return (
    <button
      type="button"
      className={cn("grid size-5 shrink-0 place-items-center text-muted-foreground", !d.children && "invisible")}
      onClick={(e) => (e.preventDefault(), toggle())}
      aria-label={open ? s.collapse : s.expand}
      aria-expanded={open}
    >
      <ChevronRight className={cn("size-4 transition-transform", open && "rotate-90")} />
    </button>
  );
}

const INDENT = { full: 20, compact: 12 };

/** A deck tree. [compact]: the sidebar's rows; otherwise Home's, with ring, counts and Study. */
export function DeckTree({ items, compact = false }: { items: DeckItem[]; compact?: boolean }) {
  const [closed, toggle] = useClosed();
  let hideBelow = Infinity;
  return (
    <div role="tree" className={compact ? "grid gap-px" : "grid gap-0.5"}>
      {items.map((d) => {
        if (d.depth > hideBelow) return null;
        hideBelow = closed.has(d.id) ? d.depth : Infinity;
        const Row = compact ? CompactRow : FullRow;
        return <Row key={d.id} d={d} items={items} open={!closed.has(d.id)} toggle={() => toggle(d.id)} />;
      })}
    </div>
  );
}

type RowProps = { d: DeckItem; items: DeckItem[]; open: boolean; toggle: () => void };

function FullRow({ d, items, open, toggle }: RowProps) {
  const ref = useRef<HTMLDivElement>(null);
  const { dragging, hint } = useDrag(ref, d, items, INDENT.full, open);
  const due = d.newCount + d.learning + d.review;
  return (
    <DeckContextMenu d={d}>
      <div
        ref={ref}
        role="treeitem"
        aria-expanded={d.children ? open : undefined}
        style={{ paddingLeft: 4 + d.depth * INDENT.full }}
        className={cn(
          "group relative flex items-center gap-3 rounded-lg py-2 pr-2 hover:bg-accent/60",
          dragging && "opacity-50",
        )}
      >
        <Chevron d={d} open={open} toggle={toggle} />
        <DeckLook icon={d.icon} color={d.color} className="text-xl" />
        <Link to={`/decks/${d.id}`} draggable={false} className="min-w-0 flex-1 after:absolute after:inset-0">
          <div className="truncate font-medium">{d.name}</div>
          <div className="truncate text-xs text-muted-foreground">
            {[d.from && s.sharedBy(d.from), d.level, d.detail].filter(Boolean).join(" · ")}
          </div>
        </Link>
        <div className="relative flex items-center gap-3">
          {!d.locked && due > 0 && <Counts d={d} />}
          {(d.notes > 0 || d.locked) && <RingMini d={d} />}
          {due > 0 && !d.locked && (
            <Button size="icon-sm" variant="secondary" asChild aria-label={`${s.study}: ${d.name}`}>
              <Link to={`/decks/${d.id}/study`} draggable={false}>
                <Play />
              </Link>
            </Button>
          )}
          <DeckMenu d={d} />
        </div>
        <DropHint hint={hint} />
      </div>
    </DeckContextMenu>
  );
}

const Counts = ({ d }: { d: DeckItem }) => (
  <span className="hidden gap-2 text-sm tabular-nums sm:flex" title={s.counts(d.newCount, d.learning, d.review)}>
    <span className={d.newCount ? "text-new" : "text-muted-foreground"}>{d.newCount}</span>
    <span className={d.learning ? "text-learning" : "text-muted-foreground"}>{d.learning}</span>
    <span className={d.review ? "text-review" : "text-muted-foreground"}>{d.review}</span>
  </span>
);

const RingMini = ({ d }: { d: DeckItem }) => <Ring value={d.ring} locked={d.locked} size={30} />;

function CompactRow({ d, items, open, toggle }: RowProps) {
  const ref = useRef<HTMLDivElement>(null);
  const { pathname } = useLocation();
  const { dragging, hint } = useDrag(ref, d, items, INDENT.compact, open);
  const here = pathname === `/decks/${d.id}` || pathname.startsWith(`/decks/${d.id}/`);
  return (
    <DeckContextMenu d={d}>
      <div
        ref={ref}
        role="treeitem"
        aria-expanded={d.children ? open : undefined}
        style={{ paddingLeft: d.depth * INDENT.compact }}
        className={cn(
          "relative flex items-center gap-1.5 rounded-md pr-2 text-sm",
          here ? "bg-accent font-medium" : "text-subtle hover:bg-accent/60",
          dragging && "opacity-50",
        )}
      >
        <Chevron d={d} open={open} toggle={toggle} />
        <Link to={`/decks/${d.id}`} draggable={false} className="flex min-w-0 flex-1 items-center gap-2 py-1.5">
          <DeckLook icon={d.icon} color={d.color} />
          <span className="truncate">{d.name}</span>
        </Link>
        {d.locked ? null : d.newCount + d.learning + d.review > 0 ? (
          <span className="text-xs text-muted-foreground tabular-nums">{d.newCount + d.learning + d.review}</span>
        ) : null}
        <DropHint hint={hint} />
      </div>
    </DeckContextMenu>
  );
}

/** Emoji people often give decks; any other can be typed. */
const EMOJI = [
  "📚",
  "📖",
  "✏️",
  "🧠",
  "💡",
  "🎓",
  "🔬",
  "🧪",
  "🧬",
  "🩺",
  "💊",
  "🫀",
  "🌍",
  "🗺️",
  "🏛️",
  "⚖️",
  "💼",
  "📈",
  "💻",
  "🧮",
  "📐",
  "🎨",
  "🎵",
  "🎹",
  "🇪🇸",
  "🇫🇷",
  "🇩🇪",
  "🇮🇹",
  "🇯🇵",
  "🇨🇳",
  "🇰🇷",
  "🇬🇧",
  "🇺🇸",
  "🇧🇷",
  "🇷🇺",
  "🇺🇦",
  "🌱",
  "🐾",
  "⭐",
  "🔥",
  "🚀",
  "🏃",
  "🍳",
  "✈️",
  "🕹️",
  "♟️",
  "📷",
  "🧩",
];

/** A deck's emoji, chosen from a grid. */
export function EmojiPicker({ value, onChange }: { value: string | null; onChange: (emoji: string | null) => void }) {
  const [open, setOpen] = useState(false);
  const choose = (emoji: string | null) => {
    onChange(emoji);
    setOpen(false);
  };
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="outline"
          size="icon-lg"
          aria-label={s.emoji}
          className="size-12 shrink-0 text-2xl"
        >
          {value ?? <span className="text-xs text-muted-foreground">{s.none}</span>}
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-auto p-2" align="start">
        <div className="grid grid-cols-8 gap-1">
          {EMOJI.map((emoji) => (
            <Button
              key={emoji}
              type="button"
              variant={emoji === value ? "secondary" : "ghost"}
              size="icon-sm"
              className="text-xl"
              aria-label={emoji}
              onClick={() => choose(emoji)}
            >
              {emoji}
            </Button>
          ))}
        </div>
        {value && (
          <Button type="button" variant="ghost" size="sm" className="mt-1 w-full" onClick={() => choose(null)}>
            {s.noEmoji}
          </Button>
        )}
      </PopoverContent>
    </Popover>
  );
}

/** A deck's colour, including none, as a radio group. */
export function ColorSwatches({ value, onChange }: { value: number; onChange: (color: number) => void }) {
  return (
    <div role="radiogroup" aria-label={s.color} className="flex flex-wrap items-center gap-2 p-1">
      {DECK_COLORS.map((_, i) => (
        <button
          key={i}
          type="button"
          role="radio"
          aria-checked={value === i}
          aria-label={s.colorName(i)}
          title={s.colorName(i)}
          onClick={() => onChange(i)}
          className={cn(
            "grid size-6 place-items-center rounded-full ring-offset-2 ring-offset-background transition-transform",
            value === i && "scale-110 ring-2 ring-ring",
            i === 0 && "border border-dashed",
          )}
          style={deckColor(i) ? { background: deckColor(i) } : undefined}
        >
          {i === 0 && <X className="size-3 text-muted-foreground" />}
        </button>
      ))}
    </div>
  );
}

/** A new deck (or sub-deck, `?new=deck&parent=…`): emoji, name and colour, then it opens. */
export function NewDeckDialog() {
  const overlay = useOverlay("new");
  const [, home] = useHome();
  const navigate = useNavigate();
  const { search } = useLocation();
  const parent = new URLSearchParams(search).get("parent");
  const [name, setName] = useState("");
  const [icon, setIcon] = useState<string | null>(null);
  const [color, setColor] = useState(0);
  const open = overlay.value === "deck";
  useEffect(() => {
    if (open) (setName(""), setIcon(null), setColor(0));
  }, [open]);
  return (
    <Dialog open={open} onOpenChange={(o) => !o && overlay.close()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{parent ? s.newSubdeck : s.newDeck}</DialogTitle>
        </DialogHeader>
        <form
          className="grid gap-5"
          onSubmit={(e) => {
            e.preventDefault();
            if (!name.trim()) return;
            home.create(name, parent, icon, color, (id) => navigate(`/decks/${id}`, { replace: true }));
          }}
        >
          <div className="flex items-center gap-3">
            <EmojiPicker value={icon} onChange={setIcon} />
            <Input
              autoFocus
              aria-label={s.deckName}
              placeholder={s.namePlaceholder}
              value={name}
              maxLength={100}
              onChange={(e) => setName(e.target.value.replace(/[\t\n]/g, ""))}
              className="h-12"
            />
          </div>
          <ColorSwatches value={color} onChange={setColor} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={overlay.close}>
              {s.cancel}
            </Button>
            <Button type="submit" disabled={!name.trim()}>
              {s.createDeck}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/** A deck's language, searchable among every one Rondo knows. */
export function LanguagePicker({
  value,
  onChange,
  any,
}: {
  value: string | null;
  onChange: (code: string | null) => void;
  /** The label for no language. */
  any: string;
}) {
  const [open, setOpen] = useState(false);
  const all = languages.get().map((code) => ({ code, name: languageName(code) }));
  all.sort((a, b) => a.name.localeCompare(b.name));
  const choose = (code: string | null) => {
    onChange(code);
    setOpen(false);
  };
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button type="button" variant="outline" className="w-full justify-between font-normal sm:w-56">
          {value ? languageName(value) : any}
          <ChevronRight className="rotate-90 opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-64 p-0" align="start">
        <Command>
          <CommandInput placeholder={s.searchLanguages} />
          <CommandList>
            <CommandEmpty>{s.nothingFound}</CommandEmpty>
            <CommandItem value={any} onSelect={() => choose(null)}>
              {any}
            </CommandItem>
            {all.map((l) => (
              <CommandItem key={l.code} value={`${l.name} ${l.code}`} onSelect={() => choose(l.code)}>
                {l.name}
              </CommandItem>
            ))}
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
}
