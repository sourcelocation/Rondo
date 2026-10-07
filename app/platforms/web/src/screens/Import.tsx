import { Upload } from "lucide-react";
import { useRef, useState } from "react";
import { Link } from "react-router";
import { Header, Loading, Spinner } from "@/components/kit";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { app, s, useScreen } from "@/rondo";
import { cn } from "cn";

const Option = ({
  title,
  hint,
  checked,
  onChange,
}: {
  title: string;
  hint: string;
  checked: boolean;
  onChange: (on: boolean) => void;
}) => (
  <label className="flex items-start justify-between gap-4">
    <span>
      <span className="block font-medium">{title}</span>
      <span className="text-sm text-muted-foreground">{hint}</span>
    </span>
    <Switch checked={checked} onCheckedChange={onChange} />
  </label>
);

/** An Anki package: editable or exactly as in Anki, with or without its review history. */
export function Import() {
  const [st, importer] = useScreen(() => app.importer());
  const [translate, setTranslate] = useState(true);
  const [history, setHistory] = useState(true);
  const [parent, setParent] = useState("*");
  const [over, setOver] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  if (!st) return <Loading />;
  const run = async (file: File) =>
    importer.run(new Int8Array(await file.arrayBuffer()), translate, history, parent === "*" ? null : parent);
  return (
    <>
      <Header up="/" title={s.importTitle} sub={s.importHint} />
      <div className="grid max-w-lg gap-6">
        <Option title={s.translate} hint={s.translateHint} checked={translate} onChange={setTranslate} />
        <Option title={s.withHistory} hint={s.withHistoryHint} checked={history} onChange={setHistory} />
        <div className="grid gap-2">
          <Label>{s.deck}</Label>
          <Select value={parent} onValueChange={setParent}>
            <SelectTrigger className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="*">{s.topLevel}</SelectItem>
              {st.decks.map((d) => (
                <SelectItem key={d.id} value={d.id}>
                  {d.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <input
          ref={input}
          type="file"
          accept=".apkg,.colpkg"
          hidden
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) run(file);
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
            const file = [...e.dataTransfer.files].find((f) => /\.(apkg|colpkg)$/i.test(f.name));
            if (file && !st.working) run(file);
          }}
          className={cn(
            "flex min-h-40 cursor-pointer flex-col items-center justify-center gap-3 rounded-xl border-2 border-dashed p-6 text-center text-muted-foreground transition-colors",
            over ? "border-ring bg-accent" : "hover:bg-accent/50",
          )}
        >
          {st.working ? <Spinner className="size-6" /> : <Upload className="size-6" />}
          <span>{st.working ? s.importing : s.dropPackage}</span>
        </div>
        {st.message && (
          <div className={st.failed ? "rounded-lg bg-warning px-4 py-3" : "rounded-lg bg-surface px-4 py-3"}>
            <p>{st.message}</p>
            {st.skipped && <p className="mt-1 text-sm text-muted-foreground">{st.skipped}</p>}
            {st.deckId && (
              <Button className="mt-3" variant="secondary" asChild>
                <Link to={`/decks/${st.deckId}`}>{s.study}</Link>
              </Button>
            )}
          </div>
        )}
      </div>
    </>
  );
}
