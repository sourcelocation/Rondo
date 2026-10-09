import {
  ArrowDown,
  ArrowUp,
  AudioLines,
  Check,
  ChevronDown,
  Image as ImageIcon,
  MoreHorizontal,
  Pencil,
  Plus,
  Trash2,
  Type,
} from "lucide-react";
import { useEffect, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router";
import { CardSurface, Side } from "@/components/Card";
import { confirm, Header, Loading, Section } from "@/components/kit";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import {
  app,
  s,
  useScreen,
  type BlockItem,
  type CardItem,
  type FieldItem,
  type TemplateItem,
  type TemplateScreen,
  type TemplateState,
} from "@/rondo";
import { useUp } from "@/routing";
import { cn } from "cn";

const IconButton = ({
  label,
  onClick,
  children,
  disabled,
}: {
  label: string;
  onClick: () => void;
  children: React.ReactNode;
  disabled?: boolean;
}) => (
  <Button type="button" size="icon-sm" variant="ghost" aria-label={label} onClick={onClick} disabled={disabled}>
    {children}
  </Button>
);

/** Note types as small cards: a sample front, the name and what it's for. */
function TemplateGrid({
  items,
  selected,
  onChoose,
  onEdit,
  onAdd,
}: {
  items: TemplateItem[];
  selected?: string;
  onChoose: (t: TemplateItem) => void;
  onEdit?: (t: TemplateItem) => void;
  onAdd?: () => void;
}) {
  const builtins = items.filter((t) => t.builtin);
  const yours = items.filter((t) => !t.builtin);
  const tile = (t: TemplateItem) => (
    <div key={t.id} className="relative min-w-0">
      <button
        type="button"
        aria-pressed={selected === t.id}
        onClick={() => onChoose(t)}
        className="group grid w-full gap-3 text-left focus-visible:outline-none"
      >
        <CardSurface
          small
          className={cn(
            "pointer-events-none items-center overflow-hidden transition-shadow group-hover:shadow-md group-focus-visible:ring-[3px] group-focus-visible:ring-ring/50",
            selected === t.id && "ring-2 ring-primary",
          )}
        >
          {t.sample && (
            <div className="w-full" style={{ zoom: 0.62 }}>
              <Side side={t.sample} />
            </div>
          )}
        </CardSurface>
        <span className="px-1 pr-9">
          <span className="block font-medium">{t.name}</span>
          <span className="block text-sm text-subtle">{t.summary}</span>
        </span>
      </button>
      {selected === t.id && <Check className="pointer-events-none absolute top-3 right-3 size-5 text-primary" />}
      {onEdit && !t.builtin && !t.anki && (
        <div className="absolute right-0 bottom-1">
          <IconButton label={`${s.editType}: ${t.name}`} onClick={() => onEdit(t)}>
            <Pencil />
          </IconButton>
        </div>
      )}
    </div>
  );
  return (
    <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
      {builtins.map(tile)}
      {(yours.length > 0 || onAdd) && (
        <div className="col-span-full mt-2 border-t pt-4 text-xs font-medium tracking-wider text-muted-foreground uppercase">
          {s.yourTypes}
        </div>
      )}
      {yours.map(tile)}
      {onAdd && (
        <button type="button" onClick={onAdd} className="group grid gap-3 text-left">
          <span className="flex min-h-32 items-center justify-center rounded-2xl border-2 border-dashed text-muted-foreground group-hover:bg-accent/50">
            <Plus className="size-6" />
          </span>
          <span className="px-1">
            <span className="block font-medium">{s.addCustom}</span>
            <span className="block text-sm text-subtle">{s.addCustomHint}</span>
          </span>
        </button>
      )}
    </div>
  );
}

type Step = { kind: "grid" } | { kind: "base" } | { kind: "edit"; id: string | null; from: string | null };

/** Choosing a note's type from previews, and making or changing your own types without leaving. */
export function TemplatePicker({
  open,
  current,
  onClose,
  onPick,
}: {
  open: boolean;
  current: string;
  onClose: () => void;
  onPick: (id: string) => void;
}) {
  const [st] = useScreen(() => app.templates(), []);
  const [step, setStep] = useState<Step>({ kind: "grid" });
  useEffect(() => {
    if (open) setStep({ kind: "grid" });
  }, [open]);
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[92dvh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{step.kind === "edit" ? s.editType : s.whatKind}</DialogTitle>
          <DialogDescription>
            {step.kind === "base" ? s.startFrom : step.kind === "grid" ? s.whatKindHint : ""}
          </DialogDescription>
        </DialogHeader>
        {!st ? (
          <Loading />
        ) : step.kind === "edit" ? (
          <TemplateEditor
            id={step.id}
            from={step.from}
            onDone={(saved) => (saved && step.id == null ? onPick(saved) : setStep({ kind: "grid" }))}
            onCancel={() => setStep({ kind: "grid" })}
          />
        ) : step.kind === "base" ? (
          <TemplateGrid
            items={st.items.filter((t) => !t.anki)}
            onChoose={(t) => setStep({ kind: "edit", id: null, from: t.id })}
          />
        ) : (
          <TemplateGrid
            items={st.items.filter((t) => !t.anki)}
            selected={current}
            onChoose={(t) => onPick(t.id)}
            onEdit={(t) => setStep({ kind: "edit", id: t.id, from: null })}
            onAdd={() => setStep({ kind: "base" })}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}

/** Your note types and the built-in ones, to look at or change. */
export function Templates() {
  const [st] = useScreen(() => app.templates());
  const navigate = useNavigate();
  const [choosing, setChoosing] = useState(false);
  if (!st) return <Loading />;
  return (
    <>
      <Header up="/settings" title={s.templates} sub={choosing ? s.startFrom : undefined} />
      {choosing ? (
        <TemplateGrid
          items={st.items.filter((t) => !t.anki)}
          onChoose={(t) => navigate(`/settings/templates/new?from=${t.id}`)}
        />
      ) : (
        <TemplateGrid
          items={st.items}
          onChoose={(t) => navigate(`/settings/templates/${t.id}`)}
          onAdd={() => setChoosing(true)}
        />
      )}
    </>
  );
}

/** Where a field sits on the card: its name in place, moved, and its options. */
function Place({
  f,
  t,
  ro,
  first,
  last,
}: {
  f: FieldItem;
  t: TemplateScreen;
  ro: boolean;
  first: boolean;
  last: boolean;
}) {
  const Icon = f.kind === "image" || f.kind === "occlusion" ? ImageIcon : f.kind === "audio" ? AudioLines : null;
  return (
    <div className="group/place flex items-center gap-2 rounded-lg border border-dashed px-3 py-2">
      {Icon && <Icon className="size-5 shrink-0 text-muted-foreground" />}
      <Input
        key={f.name}
        aria-label={s.field}
        defaultValue={f.name}
        disabled={ro}
        onBlur={(e) => {
          const name = e.target.value.trim();
          if (name && name !== f.name) t.renameField(f.id, name);
          else e.target.value = f.name;
        }}
        onKeyDown={(e) => e.key === "Enter" && e.currentTarget.blur()}
        className="h-auto flex-1 border-0 bg-transparent px-1 text-center shadow-none focus-visible:bg-accent/50 dark:bg-transparent"
      />
      {!ro && (
        <div className="flex shrink-0 items-center sm:opacity-0 sm:group-focus-within/place:opacity-100 sm:group-hover/place:opacity-100">
          <IconButton label={s.moveUp} disabled={first} onClick={() => t.moveOnCard(f.id, -1)}>
            <ArrowUp />
          </IconButton>
          <IconButton label={s.moveDown} disabled={last} onClick={() => t.moveOnCard(f.id, 1)}>
            <ArrowDown />
          </IconButton>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button type="button" variant="ghost" size="icon-sm" aria-label={`${s.more}: ${f.name}`}>
                <MoreHorizontal />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              {f.kind === "text" && (
                <>
                  <DropdownMenuCheckboxItem checked={f.tts} onCheckedChange={(on) => t.setTts(f.id, on)}>
                    {s.inDeckLanguage}
                  </DropdownMenuCheckboxItem>
                  <DropdownMenuSeparator />
                </>
              )}
              <DropdownMenuItem
                variant="destructive"
                onSelect={async () =>
                  (await confirm(`${s.remove} “${f.name}”?`, undefined, s.remove)) && t.removeField(f.id)
                }
              >
                <Trash2 /> {s.remove}
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      )}
    </div>
  );
}

/** One side of the card being shaped: its field places, and adding a field at its end. */
function PlacesSide({ front, st, t }: { front: boolean; st: TemplateState; t: TemplateScreen }) {
  const fields = front ? st.front : st.back;
  const ro = st.readOnly;
  return (
    <div className="grid gap-2">
      <span className="text-xs font-medium tracking-wider text-muted-foreground uppercase">
        {front ? s.front : s.backSide}
      </span>
      {fields.map((f, i) => (
        <Place key={f.id} f={f} t={t} ro={ro} first={i === 0} last={i === fields.length - 1} />
      ))}
      {!ro && (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button type="button" variant="ghost" size="sm" className="justify-self-center">
              <Plus /> {s.addTo(front)}
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent>
            <DropdownMenuItem onSelect={() => t.addFieldTo("text", front)}>
              <Type /> {s.text}
            </DropdownMenuItem>
            <DropdownMenuItem onSelect={() => t.addFieldTo("image", front)}>
              <ImageIcon /> {s.image}
            </DropdownMenuItem>
            <DropdownMenuItem onSelect={() => t.addFieldTo("audio", front)}>
              <AudioLines /> {s.audio}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </div>
  );
}

const Question = ({
  title,
  hint,
  on,
  ro,
  onChange,
}: {
  title: string;
  hint: string;
  on: boolean;
  ro: boolean;
  onChange: (on: boolean) => void;
}) => (
  <label className="flex items-center justify-between gap-4 py-2">
    <span>
      <span className="block font-medium">{title}</span>
      <span className="block text-sm text-subtle">{hint}</span>
    </span>
    <Switch checked={on} disabled={ro} onCheckedChange={onChange} />
  </label>
);

/**
 * A note type, shaped on the card: fields placed on the front and back of its first card, the
 * questions it asks, and every card block by block for more. [onDone] gets the saved type's id.
 */
export function TemplateEditor({
  id,
  from,
  onDone,
  onCancel,
}: {
  id: string | null;
  from: string | null;
  onDone: (saved: string | null) => void;
  onCancel?: () => void;
}) {
  const [st, t] = useScreen(() => app.template(id, from), [id, from]);
  const saved = st?.saved;
  useEffect(() => {
    if (saved && st) onDone(st.id);
  }, [saved]); // eslint-disable-line react-hooks/exhaustive-deps
  if (!st) return <Loading />;
  const ro = st.readOnly;
  return (
    <div className="grid gap-8">
      {ro ? (
        <h2>{st.name}</h2>
      ) : (
        <Input
          key={st.id}
          aria-label={s.name}
          className="h-auto border-0 bg-transparent px-0 font-serif text-3xl shadow-none focus-visible:bg-accent/40 dark:bg-transparent"
          defaultValue={st.name}
          onBlur={(e) => t.rename(e.target.value)}
        />
      )}
      {st.each === 0 || st.front.length + st.back.length > 0 ? (
        <CardSurface className="items-stretch gap-4">
          <PlacesSide front st={st} t={t} />
          <hr className="border-border" />
          <PlacesSide front={false} st={st} t={t} />
        </CardSurface>
      ) : null}
      {st.problem && <p className="-mt-4 rounded-lg bg-warning px-4 py-3 text-sm">{st.problem}</p>}
      {(st.bothWays != null || st.typeAnswer != null || st.listening != null) && (
        <Section title={s.questions} className="mb-0">
          {st.bothWays != null && (
            <Question
              title={s.bothWays}
              hint={s.bothWaysHint}
              on={st.bothWays}
              ro={ro}
              onChange={(on) => t.setBothWays(on)}
            />
          )}
          {st.typeAnswer != null && (
            <Question
              title={s.typeAnswer}
              hint={s.typeAnswerHint}
              on={st.typeAnswer}
              ro={ro}
              onChange={(on) => t.setTypeAnswer(on)}
            />
          )}
          {st.listening != null && (
            <Question
              title={s.listening}
              hint={s.listeningHint}
              on={st.listening}
              ro={ro}
              onChange={(on) => t.setListening(on)}
            />
          )}
        </Section>
      )}
      {st.inUse > 0 && <p className="text-sm text-muted-foreground">{s.affects(st.inUse)}</p>}
      <details className="group">
        <summary className="flex cursor-pointer list-none items-center justify-between rounded-lg py-2 text-sm text-muted-foreground hover:text-foreground">
          {s.everyCard}
          <ChevronDown className="size-4 transition-transform group-open:rotate-180" />
        </summary>
        <div className="mt-4">
          <Advanced st={st} t={t} />
        </div>
      </details>
      {st.preview.length === 2 && (
        <Section title={s.preview} className="mb-0">
          <div className="grid gap-4 sm:grid-cols-2">
            {st.preview.map((side, i) => (
              <CardSurface key={i} small>
                <Side side={side} />
              </CardSurface>
            ))}
          </div>
        </Section>
      )}
      <div className="flex flex-wrap items-center justify-end gap-2">
        {!st.builtin && !ro && id && st.inUse === 0 && (
          <Button
            variant="ghost"
            className="mr-auto text-destructive"
            onClick={async () => (await confirm(`${s.delete} “${st.name}”?`)) && t.delete()}
          >
            <Trash2 /> {s.delete}
          </Button>
        )}
        {onCancel && (
          <Button variant="ghost" onClick={onCancel}>
            {s.cancel}
          </Button>
        )}
        {st.builtin ? (
          <Button onClick={() => t.duplicate()}>{s.duplicate}</Button>
        ) : (
          !ro && (
            <Button onClick={() => t.save()} disabled={!!st.problem}>
              {id ? s.save : s.add}
            </Button>
          )
        )}
      </div>
    </div>
  );
}

/** A note type as a page: from Settings › General › Note types. */
export function TemplatePage() {
  const { id = "" } = useParams();
  const [params] = useSearchParams();
  const goUp = useUp();
  const isNew = id === "new";
  return (
    <>
      <Header up="/settings/templates" title={isNew ? s.newTemplate : s.editType} />
      <TemplateEditor id={isNew ? null : id} from={params.get("from")} onDone={() => goUp("/settings/templates")} />
    </>
  );
}

/** Every field and every card's sides, block by block: for what the card view can't say. */
function Advanced({ st, t }: { st: TemplateState; t: TemplateScreen }) {
  const ro = st.readOnly;
  return (
    <>
      <Section
        title={s.fields}
        action={
          !ro && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="sm">
                  <Plus /> {s.addField}
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent>
                {["text", "image", "audio", "occlusion"].map((k) => (
                  <DropdownMenuItem key={k} onSelect={() => t.addField(k)}>
                    {s.kind(k)}
                  </DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
          )
        }
      >
        {st.fields.map((f, i) => (
          <div key={f.id} className="flex items-center gap-2 py-1">
            <Input
              key={f.name}
              className="flex-1"
              defaultValue={f.name}
              disabled={ro}
              onBlur={(e) => t.renameField(f.id, e.target.value)}
            />
            <span className="w-28 text-sm text-muted-foreground">{s.kind(f.kind)}</span>
            {!ro && (
              <>
                <IconButton label={s.moveUp} disabled={i === 0} onClick={() => t.moveField(f.id, -1)}>
                  <ArrowUp />
                </IconButton>
                <IconButton
                  label={s.moveDown}
                  disabled={i === st.fields.length - 1}
                  onClick={() => t.moveField(f.id, 1)}
                >
                  <ArrowDown />
                </IconButton>
              </>
            )}
          </div>
        ))}
        <div className="mt-3 flex items-center gap-3">
          <span className="text-sm">{s.oneCardEach}</span>
          <Select value={String(st.each)} disabled={ro} onValueChange={(f) => t.setEach(+f)}>
            <SelectTrigger className="w-48">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="0">{s.none}</SelectItem>
              {st.fields
                .filter((f) => f.kind === "text" || f.kind === "occlusion")
                .map((f) => (
                  <SelectItem key={f.id} value={String(f.id)}>
                    {f.name}
                  </SelectItem>
                ))}
            </SelectContent>
          </Select>
        </div>
      </Section>
      <Section
        title={s.cardsTitle}
        className="mb-0"
        action={
          !ro &&
          st.each === 0 && (
            <Button variant="ghost" size="sm" onClick={() => t.addCard()}>
              <Plus /> {s.addCard}
            </Button>
          )
        }
      >
        <div className="grid gap-6">
          {st.cards.map((c) => (
            <div key={c.id} className="grid gap-3 rounded-lg border p-4">
              <div className="flex items-center gap-2">
                <Input
                  key={c.name}
                  className="flex-1"
                  defaultValue={c.name}
                  disabled={ro}
                  onBlur={(e) => t.renameCard(c.id, e.target.value)}
                />
                {!ro && st.cards.length > 1 && (
                  <IconButton label={s.remove} onClick={() => t.removeCard(c.id)}>
                    <Trash2 />
                  </IconButton>
                )}
              </div>
              <div className="grid gap-4 sm:grid-cols-2">
                <Blocks card={c} back={false} blocks={c.front} fields={st.fields} t={t} readOnly={ro} />
                <Blocks card={c} back blocks={c.back} fields={st.fields} t={t} readOnly={ro} />
              </div>
            </div>
          ))}
        </div>
      </Section>
    </>
  );
}

function Blocks({
  card,
  back,
  blocks,
  fields,
  t,
  readOnly,
}: {
  card: CardItem;
  back: boolean;
  blocks: BlockItem[];
  fields: FieldItem[];
  t: TemplateScreen;
  readOnly: boolean;
}) {
  const name = (b: BlockItem) =>
    ({ divider: s.divider, label: s.label, typein: `${s.typein}: ${fields.find((f) => f.id === b.field)?.name}` })[
      b.kind as "divider"
    ] ?? fields.find((f) => f.id === b.field)?.name;
  return (
    <div className="grid gap-1">
      <div className="text-xs font-medium text-muted-foreground uppercase">{back ? s.backSide : s.front}</div>
      {blocks.map((b, i) => (
        <div key={i} className="flex items-center gap-2 rounded-md bg-surface px-2 py-1">
          {b.kind === "label" ? (
            <Input
              className="h-8 flex-1"
              defaultValue={b.text}
              disabled={readOnly}
              onBlur={(e) => t.setBlock(card.id, back, i, b.size, b.center, e.target.value)}
            />
          ) : (
            <span className="flex-1 truncate text-sm">{name(b)}</span>
          )}
          {(b.kind === "field" || b.kind === "label") && (
            <Select
              value={b.size}
              disabled={readOnly}
              onValueChange={(size) => t.setBlock(card.id, back, i, size, b.center, b.text)}
            >
              <SelectTrigger size="sm" className="w-28">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="small">{s.small}</SelectItem>
                <SelectItem value="normal">{s.normal}</SelectItem>
                <SelectItem value="large">{s.large}</SelectItem>
              </SelectContent>
            </Select>
          )}
          {!readOnly && (
            <>
              <IconButton label={s.moveUp} disabled={i === 0} onClick={() => t.moveBlock(card.id, back, i, -1)}>
                <ArrowUp />
              </IconButton>
              <IconButton
                label={s.moveDown}
                disabled={i === blocks.length - 1}
                onClick={() => t.moveBlock(card.id, back, i, 1)}
              >
                <ArrowDown />
              </IconButton>
              <IconButton label={s.remove} onClick={() => t.removeBlock(card.id, back, i)}>
                <Trash2 />
              </IconButton>
            </>
          )}
        </div>
      ))}
      {!readOnly && (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button type="button" variant="ghost" size="sm" className="justify-self-start">
              <Plus /> {s.addBlock}
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent>
            {fields.map((f) => (
              <DropdownMenuItem key={f.id} onSelect={() => t.addBlock(card.id, back, "field", f.id)}>
                {f.name}
              </DropdownMenuItem>
            ))}
            <DropdownMenuSeparator />
            <DropdownMenuItem onSelect={() => t.addBlock(card.id, back, "divider", 0)}>{s.divider}</DropdownMenuItem>
            <DropdownMenuItem onSelect={() => t.addBlock(card.id, back, "label", 0)}>{s.label}</DropdownMenuItem>
            {fields
              .filter((f) => f.kind === "text")
              .map((f) => (
                <DropdownMenuItem key={`t${f.id}`} onSelect={() => t.addBlock(card.id, back, "typein", f.id)}>
                  {s.typein}: {f.name}
                </DropdownMenuItem>
              ))}
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </div>
  );
}
