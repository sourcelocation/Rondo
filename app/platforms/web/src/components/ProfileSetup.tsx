import { ChevronRight } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { countryName, Flag, flagCodes } from "@/components/Flag";
import { Button } from "@/components/ui/button";
import { Command, CommandEmpty, CommandInput, CommandItem, CommandList } from "@/components/ui/command";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { app, date, s, useApp, useScreen } from "@/rondo";

/** Picks a country's flag, searchable by name. */
export function FlagPicker({ value, onChange }: { value: string | null; onChange: (code: string | null) => void }) {
  const [open, setOpen] = useState(false);
  const choose = (code: string | null) => (onChange(code), setOpen(false));
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button type="button" variant="outline" className="w-full justify-between font-normal">
          <span className="flex items-center gap-2">
            <Flag code={value} />
            {value ? countryName(value) : s.noFlag}
          </span>
          <ChevronRight className="rotate-90 opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-72 p-0" align="start">
        <Command>
          <CommandInput placeholder={s.search} />
          <CommandList>
            <CommandEmpty>{s.nothingFound}</CommandEmpty>
            <CommandItem value={s.noFlag} onSelect={() => choose(null)}>
              {s.noFlag}
            </CommandItem>
            {flagCodes().map((code) => (
              <CommandItem key={code} value={`${countryName(code)} ${code}`} onSelect={() => choose(code)}>
                <Flag code={code} /> {countryName(code)}
              </CommandItem>
            ))}
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
}

/** The profile setup, all filled in so one tap confirms it. Shown when the app says so. */
export function ProfileSetup() {
  const open = useApp()?.setup ?? false;
  return open ? <Setup /> : null;
}

function Setup() {
  const [st, setup] = useScreen(() => app.profileSetup());
  const [username, setUsername] = useState("");
  const [name, setName] = useState("");
  const [flag, setFlag] = useState<string | null>(null);
  const started = useRef(false);
  useEffect(() => {
    if (!st || started.current) return;
    started.current = true;
    setUsername(st.username);
    setName(st.name);
    setFlag(st.flag ?? null);
  }, [st]);
  useEffect(() => {
    if (!st || !username || username === st.username) return;
    const t = window.setTimeout(() => setup.check(username), 350);
    return () => clearTimeout(t);
  }, [username, st, setup]);
  const locked = st?.usernameLocked ?? null;
  return (
    <Dialog open onOpenChange={(o) => !o && setup.skip()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{s.setupTitle}</DialogTitle>
          <DialogDescription>{s.setupHint}</DialogDescription>
        </DialogHeader>
        <form className="grid gap-5" onSubmit={(e) => (e.preventDefault(), setup.save(username, name, flag))}>
          <div className="grid gap-2">
            <Label htmlFor="setup-username">{s.username}</Label>
            <div className="relative">
              <span className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-muted-foreground">
                @
              </span>
              <Input
                id="setup-username"
                className="pl-7"
                autoComplete="username"
                value={username}
                disabled={!!locked}
                onChange={(e) => setUsername(e.target.value.toLowerCase())}
              />
            </div>
            <p className="text-xs text-muted-foreground">
              {locked
                ? s.usernameChangesOn(date(locked))
                : st?.username === username && st.usernameNote
                  ? st.usernameNote
                  : s.usernameHint}
              {st?.username === username && st.suggestion && (
                <>
                  {" "}
                  <button type="button" className="underline" onClick={() => setUsername(st.suggestion!)}>
                    {s.tryInstead(st.suggestion)}
                  </button>
                </>
              )}
            </p>
          </div>
          <div className="grid gap-2">
            <Label htmlFor="setup-name">{s.displayName}</Label>
            <Input id="setup-name" autoComplete="name" value={name} onChange={(e) => setName(e.target.value)} />
            <p className="text-xs text-muted-foreground">{s.displayNameHint}</p>
          </div>
          <div className="grid gap-2">
            <Label>{s.flag}</Label>
            <FlagPicker value={flag} onChange={setFlag} />
          </div>
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => setup.skip()}>
              {s.notNow}
            </Button>
            <Button type="submit" disabled={!st || st.busy || (st.username === username && !!st.usernameNote)}>
              {s.looksGood}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
