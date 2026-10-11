import { FileUp, Upload } from "lucide-react";
import { useRef, useState } from "react";
import { Link } from "react-router";
import { CardSurface, Side } from "@/components/Card";
import { Header, Loading, Row, Rows, Section, Spinner } from "@/components/kit";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import {
  app,
  FileFormat,
  languageName,
  s,
  useScreen,
  type AnkiState,
  type CsvState,
  type ImportScreen,
  type ImportState,
  type SuggestionItem,
} from "@/rondo";
import { cn } from "cn";

const SEPARATORS = [",", "\t", ";", "|"];
/** Select items can't be empty: what stands for "leave this column out" and "a new deck". */
const NONE = "*";

const Field = ({ label, children }: { label: string; children: React.ReactNode }) => (
  <div className="grid gap-2">
    <Label>{label}</Label>
    {children}
  </div>
);

const Choose = ({
  value,
  onChange,
  items,
  label,
  className,
}: {
  value: string;
  onChange: (v: string) => void;
  items: { id: string; label: string }[];
  label?: string;
  className?: string;
}) => (
  <Select value={value} onValueChange={onChange}>
    <SelectTrigger className={cn("w-full", className)} aria-label={label}>
      <SelectValue />
    </SelectTrigger>
    <SelectContent>
      {items.map((i) => (
        <SelectItem key={i.id} value={i.id}>
          {i.label}
        </SelectItem>
      ))}
    </SelectContent>
  </Select>
);

/** Choosing what to import: a file dropped or picked, or rows pasted. */
function Start({ st, importer }: { st: ImportState; importer: ImportScreen }) {
  const [over, setOver] = useState(false);
  const [text, setText] = useState("");
  const input = useRef<HTMLInputElement>(null);
  const open = async (file: File) => importer.open(new Int8Array(await file.arrayBuffer()), file.name);
  return (
    <div className="grid gap-8">
      <input
        ref={input}
        type="file"
        accept=".apkg,.colpkg,.rondo,.zip,.csv,.tsv,.txt"
        hidden
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) open(file);
          e.target.value = "";
        }}
      />
      <div
        role="button"
        tabIndex={0}
        aria-disabled={st.working}
        onClick={() => !st.working && input.current?.click()}
        onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && !st.working && input.current?.click()}
        onDragOver={(e) => (e.preventDefault(), setOver(true))}
        onDragLeave={() => setOver(false)}
        onDrop={(e) => {
          e.preventDefault();
          setOver(false);
          const file = e.dataTransfer.files[0];
          if (file && !st.working) open(file);
        }}
        className={cn(
          "flex min-h-40 cursor-pointer flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed p-6 text-center text-muted-foreground transition-colors",
          over ? "border-ring bg-accent" : "hover:bg-accent/50",
        )}
      >
        {st.working ? <Spinner className="size-6" /> : <Upload className="size-6" />}
        <span className="font-medium text-foreground">{st.working ? s.importing : s.dropAnyFile}</span>
        <span className="text-sm">{s.fileKinds}</span>
      </div>
      <div className="grid gap-2">
        <Label htmlFor="paste">{s.orPaste}</Label>
        <p className="text-sm text-subtle">{s.pasteHint}</p>
        <Textarea
          id="paste"
          rows={5}
          className="font-mono text-sm"
          value={text}
          placeholder={s.pastePlaceholder.replace(/\\t/g, "\t").replace(/\\n/g, "\n")}
          onChange={(e) => setText(e.target.value)}
        />
        <Button
          variant="secondary"
          className="justify-self-start"
          disabled={!text.trim()}
          onClick={() => importer.paste(text)}
        >
          {s.readRows}
        </Button>
      </div>
    </div>
  );
}

/** Where a Rondo file goes, and whether your progress comes with it. */
function Package({ st, importer }: { st: ImportState; importer: ImportScreen }) {
  const [history, setHistory] = useState(true);
  const [parent, setParent] = useState(NONE);
  return (
    <div className="grid gap-6">
      <Rows>
        <Row
          label
          title={s.withProgress}
          hint={s.withProgressHint}
          action={<Switch checked={history} onCheckedChange={setHistory} />}
        />
      </Rows>
      <Field label={s.deck}>
        <Choose
          value={parent}
          onChange={setParent}
          items={[{ id: NONE, label: s.topLevel }, ...st.decks.map((d) => ({ id: d.id, label: d.label }))]}
        />
      </Field>
      <Button
        size="lg"
        className="justify-self-start"
        disabled={st.working}
        onClick={() => importer.run(false, history, parent === NONE ? null : parent)}
      >
        {st.working ? <Spinner /> : <FileUp />} {st.working ? s.importing : s.importAction}
      </Button>
    </div>
  );
}

/** A suggestion on top of an Anki package's conversion, in words. */
function suggestion(g: SuggestionItem) {
  if (g.kind === "title") return s.suggestTitle(g.name);
  if (g.kind === "large") return s.suggestLarge(g.name);
  return s.suggestLanguage(g.name, languageName(g.language ?? ""));
}

/** An Anki package as it will import: the decks it fills, its note types with their first card, its suggestions, and what comes. */
function AnkiPackage({ st, anki, importer }: { st: ImportState; anki: AnkiState; importer: ImportScreen }) {
  const [history, setHistory] = useState(true);
  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-8">
      <div className="grid gap-1">
        <p>{s.ankiSummary(anki.notes, anki.cards, anki.media, anki.reviews)}</p>
        {anki.repeated > 0 && <p className="text-sm text-subtle">{s.repeated(anki.repeated)}</p>}
      </div>
      <Section className="mb-0" title={s.ankiDecks}>
        <Rows>
          {anki.decks.map((d) => (
            <Row key={d.path.join("\n")} title={d.path.join(" › ")} hint={s.ankiDeck(d.notes, d.existing)} />
          ))}
        </Rows>
      </Section>
      <Section className="mb-0" title={s.ankiTypes}>
        <div className="grid gap-6">
          {anki.types.map((t, i) => (
            <div key={i} className="grid gap-3">
              <div className="flex items-baseline justify-between gap-4">
                <span className="font-medium">{t.name}</span>
                <span className="text-sm text-subtle">{s.notes(t.notes)}</span>
              </div>
              {t.kept && <p className="text-sm text-subtle">{t.kept}</p>}
              {t.sample && (
                <CardSurface small className="grid gap-4 sm:grid-cols-2">
                  <Side side={t.sample.front} />
                  <Side side={t.sample.back} />
                </CardSurface>
              )}
            </div>
          ))}
        </div>
      </Section>
      {anki.suggestions.length > 0 && !anki.keepLook && (
        <Section className="mb-0" title={s.suggestions}>
          <Rows>
            {anki.suggestions.map((g) => (
              <Row
                key={g.index}
                label
                title={suggestion(g)}
                action={<Switch checked={g.chosen} onCheckedChange={(on) => importer.setSuggestion(g.index, on)} />}
              />
            ))}
          </Rows>
        </Section>
      )}
      {(anki.canConvert || anki.reviews > 0) && (
        <Rows>
          {anki.canConvert && (
            <Row
              label
              title={s.keepAnkiLook}
              hint={s.keepAnkiLookHint}
              action={
                <Switch checked={anki.keepLook} onCheckedChange={(on) => importer.setAnki(on, anki.parentId ?? null)} />
              }
            />
          )}
          {anki.reviews > 0 && (
            <Row
              label
              title={s.withHistory}
              hint={s.withHistoryHint}
              action={<Switch checked={history} onCheckedChange={setHistory} />}
            />
          )}
        </Rows>
      )}
      <Field label={s.deck}>
        <Choose
          value={anki.parentId ?? NONE}
          onChange={(id) => importer.setAnki(anki.keepLook, id === NONE ? null : id)}
          items={[{ id: NONE, label: s.topLevel }, ...st.decks.map((d) => ({ id: d.id, label: d.label }))]}
        />
      </Field>
      <Button
        size="lg"
        className="justify-self-start"
        disabled={st.working || anki.notes === 0}
        onClick={() => importer.run(!anki.keepLook, history, anki.parentId ?? null)}
      >
        {st.working ? <Spinner /> : <FileUp />} {st.working ? s.importing : s.importAction}
      </Button>
    </div>
  );
}

/** A CSV's columns, each sent to a field or left out, with its first rows and its first card. */
function Csv({ st, csv, importer }: { st: ImportState; csv: CsvState; importer: ImportScreen }) {
  const [name, setName] = useState(csv.deckName);
  const targets = [{ id: NONE, label: s.leaveOut }, ...csv.targets.map((t) => ({ id: t.id, label: t.label }))];
  return (
    // One column no wider than the page: the table scrolls inside it.
    <div className="grid grid-cols-[minmax(0,1fr)] gap-8">
      {st.fromAnki && <p className="rounded-lg bg-warning px-4 py-3 text-sm">{s.fromAnkiText}</p>}
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label={s.noteType}>
          <Choose value={csv.templateId} onChange={(id) => importer.setTemplate(id)} items={csv.templates} />
        </Field>
        <Field label={s.deck}>
          <Choose
            value={csv.deckId ?? NONE}
            onChange={(id) => importer.setDeck(id === NONE ? null : id, name)}
            items={[{ id: NONE, label: s.newDeck }, ...st.decks.map((d) => ({ id: d.id, label: d.label }))]}
          />
          {csv.deckId == null && (
            <Input
              aria-label={s.deckName}
              value={name}
              maxLength={100}
              onChange={(e) => setName(e.target.value)}
              onBlur={() => importer.setDeck(null, name)}
            />
          )}
        </Field>
      </div>
      <Section
        className="mb-0"
        title={`${s.columns} · ${s.rows(csv.rows)}`}
        action={
          <div className="flex flex-wrap items-center gap-3 text-sm">
            <Choose
              label={s.separator}
              className="h-8 w-36"
              value={csv.separator}
              onChange={(v) => importer.setSeparator(v)}
              items={SEPARATORS.map((sep) => ({ id: sep, label: s.separatorName(sep) }))}
            />
            <label className="flex items-center gap-2">
              <Switch checked={csv.header} onCheckedChange={(on) => importer.setHeader(on)} />
              {s.firstRowNames}
            </label>
          </div>
        }
      >
        <div className="overflow-x-auto rounded-lg bg-card shadow-sm">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b">
                {csv.titles.map((title, i) => (
                  <th key={i} className="min-w-40 p-2 text-left align-top font-normal">
                    <div className="mb-1 truncate px-1 text-xs text-muted-foreground">{title}</div>
                    <Choose
                      label={title}
                      className="h-8"
                      value={csv.columns[i] || NONE}
                      onChange={(v) => importer.setColumn(i, v === NONE ? "" : v)}
                      items={targets}
                    />
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {csv.preview.map((row, r) => (
                <tr key={r} className="border-b last:border-0">
                  {row.map((cell, c) => (
                    <td
                      key={c}
                      className={cn("max-w-60 truncate px-3 py-2", !csv.columns[c] && "text-muted-foreground/60")}
                    >
                      {cell}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {csv.html && <p className="mt-2 text-sm text-subtle">{s.htmlCells}</p>}
      </Section>
      <Section className="mb-0" title={s.firstCard}>
        {csv.sample ? (
          <CardSurface small className="grid gap-4 sm:grid-cols-2">
            <Side side={csv.sample.front} />
            <Side side={csv.sample.back} />
          </CardSurface>
        ) : (
          <p className="text-sm text-subtle">{s.noFirstCard}</p>
        )}
      </Section>
      <Button
        size="lg"
        className="justify-self-start"
        disabled={st.working || !csv.sample}
        onClick={() => importer.run(false, false, null)}
      >
        {st.working ? <Spinner /> : <FileUp />} {st.working ? s.importing : s.importRows(csv.rows)}
      </Button>
    </div>
  );
}

/** One screen for every import: Anki packages, Rondo files and spreadsheets of cards. */
export function Import() {
  const [st, importer] = useScreen(() => app.importer());
  if (!st) return <Loading />;
  const title = st.kind && s.importFormat(st.kind);
  return (
    <>
      <Header up="/" title={s.importTitle} sub={title ? `${title}${st.file ? ` · ${st.file}` : ""}` : s.importHint}>
        {st.kind && (
          <Button variant="ghost" onClick={() => importer.reset()}>
            {s.chooseAnother}
          </Button>
        )}
      </Header>
      <div className="max-w-3xl">
        {st.message && (
          <div className={cn("mb-8 rounded-lg px-4 py-3", st.failed ? "bg-warning" : "bg-surface")}>
            <p>{st.message}</p>
            {st.skipped && <p className="mt-1 text-sm text-muted-foreground">{st.skipped}</p>}
            {st.deckId && (
              <Button className="mt-3" variant="secondary" asChild>
                <Link to={`/decks/${st.deckId}`}>{s.study}</Link>
              </Button>
            )}
          </div>
        )}
        {!st.kind && <Start st={st} importer={importer} />}
        {st.kind === FileFormat.ANKI && st.anki && <AnkiPackage st={st} anki={st.anki} importer={importer} />}
        {st.kind === FileFormat.RONDO && <Package st={st} importer={importer} />}
        {st.kind === FileFormat.CSV && st.csv && <Csv key={st.file ?? ""} st={st} csv={st.csv} importer={importer} />}
      </div>
    </>
  );
}
