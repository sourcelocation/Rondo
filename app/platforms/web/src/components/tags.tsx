import { Check, Tag, X } from "lucide-react";
import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { app, s, useScreen } from "@/rondo";
import { cn } from "cn";

const same = (a: string, b: string) => a.toLowerCase() === b.toLowerCase();

/** A tag, with a way to take it off when [onRemove] is given. */
export function TagChip({ tag, onRemove }: { tag: string; onRemove?: () => void }) {
  return (
    <Badge variant="secondary" className="max-w-full gap-1 font-normal">
      <span className="truncate">{tag}</span>
      {onRemove && (
        <button type="button" aria-label={`${s.delete}: ${tag}`} onClick={onRemove} className="-mr-1 rounded-full">
          <X className="size-3" />
        </button>
      )}
    </Badge>
  );
}

/** Tags notes have that contain [query]; with none typed, the top-level ones. */
export function useTags(query: string): string[] {
  const [st, tags] = useScreen(() => app.tags(), []);
  const ready = !!st;
  useEffect(() => {
    if (ready) tags.search(query);
  }, [query, ready, tags]);
  return st?.tags ?? [];
}

/**
 * Typing tags: the ones added as chips, and suggestions from the notes' tags. Space, Enter or a
 * suggestion adds; Backspace in an empty box takes the last one off.
 */
export function TagInput({
  value,
  onAdd,
  onRemove,
  disabled,
  autoFocus,
}: {
  value: string[];
  onAdd: (text: string) => void;
  onRemove: (tag: string) => void;
  disabled?: boolean;
  autoFocus?: boolean;
}) {
  const [text, setText] = useState("");
  const typed = text.trim();
  const suggestions = useTags(typed)
    .filter((t) => !value.some((v) => same(v, t)))
    .slice(0, 8);
  const add = (tag: string) => {
    if (tag.trim()) onAdd(tag.trim());
    setText("");
  };
  return (
    <div className="grid gap-2">
      <div
        className={cn(
          "flex min-h-9 flex-wrap items-center gap-1.5 rounded-md border bg-transparent px-2 py-1.5 focus-within:ring-[3px] focus-within:ring-ring/50",
          disabled && "opacity-60",
        )}
      >
        <Tag className="size-4 shrink-0 text-muted-foreground" />
        {value.map((t) => (
          <TagChip key={t} tag={t} onRemove={disabled ? undefined : () => onRemove(t)} />
        ))}
        {!disabled && (
          <input
            aria-label={s.addTag}
            placeholder={value.length ? "" : s.addTag}
            value={text}
            autoFocus={autoFocus}
            className="min-w-24 flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
            onChange={(e) => (/\s$/.test(e.target.value) ? add(e.target.value) : setText(e.target.value))}
            onKeyDown={(e) => {
              if (e.key === "Enter" && typed) (e.preventDefault(), add(typed));
              if (e.key === "Backspace" && !text && value.length) onRemove(value[value.length - 1]!);
            }}
            onBlur={() => typed && add(typed)}
          />
        )}
      </div>
      {!disabled && typed && suggestions.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {suggestions.map((t) => (
            <button
              key={t}
              type="button"
              className="rounded-full border px-2 py-0.5 text-xs text-subtle hover:bg-accent"
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => add(t)}
            >
              {t}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/** Browse's tag filter: notes with any of the tags picked, or a tag under one. */
export function TagFilter({ value, onChange }: { value: string[]; onChange: (tags: string[]) => void }) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState("");
  const found = useTags(text.trim());
  const toggle = (tag: string) =>
    onChange(value.some((v) => same(v, tag)) ? value.filter((v) => !same(v, tag)) : [...value, tag]);
  return (
    <Popover open={open} onOpenChange={(o) => (setOpen(o), o || setText(""))}>
      <PopoverTrigger asChild>
        <Button variant="outline" size="sm" className={cn("max-w-56 font-normal", value.length && "border-primary")}>
          <Tag /> <span className="truncate">{value.length ? value.join(", ") : s.anyTag}</span>
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-72 p-2" align="start">
        <Input
          autoFocus
          placeholder={s.search}
          value={text}
          onChange={(e) => setText(e.target.value)}
          className="mb-2"
        />
        <div className="max-h-72 overflow-y-auto">
          {found.length === 0 && <p className="px-2 py-3 text-sm text-muted-foreground">{s.noTags}</p>}
          {found.map((t) => {
            const on = value.some((v) => same(v, t));
            return (
              <button
                key={t}
                type="button"
                className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-accent"
                onClick={() => toggle(t)}
              >
                <Check className={cn("size-4 shrink-0", !on && "invisible")} />
                <span className="truncate">{t}</span>
              </button>
            );
          })}
        </div>
        {value.length > 0 && (
          <Button variant="ghost" size="sm" className="mt-1 w-full" onClick={() => onChange([])}>
            {s.anyTag}
          </Button>
        )}
      </PopoverContent>
    </Popover>
  );
}
