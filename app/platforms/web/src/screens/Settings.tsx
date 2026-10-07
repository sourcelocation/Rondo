import { ChevronRight, Copy, KeyRound, TriangleAlert } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router";
import { toast } from "sonner";
import { Flag } from "@/components/Flag";
import { confirm, Header, Loading, Section } from "@/components/kit";
import { signOut } from "@/components/Shell";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Slider } from "@/components/ui/slider";
import { Switch } from "@/components/ui/switch";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { app, date, s, useApp, useScreen } from "@/rondo";

const go = (url: string) => (location.href = url);

function Choice({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: number;
  options: [number, string][];
  onChange: (v: number) => void;
}) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <Label>{label}</Label>
      <ToggleGroup type="single" variant="outline" value={String(value)} onValueChange={(v) => v && onChange(+v)}>
        {options.map(([v, name]) => (
          <ToggleGroupItem key={v} value={String(v)} className="px-3">
            {name}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
    </div>
  );
}

/** Minutes after midnight as an `<input type="time">` value, and back. */
const clock = (minutes: number) =>
  `${String(Math.floor(minutes / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`;
const minutesOf = (time: string) => {
  const [h, m] = time.split(":").map(Number);
  return h === undefined || m === undefined || isNaN(h) || isNaN(m) ? null : h * 60 + m;
};

/** When to be reminded, and how: on this device (asking to show notifications), by email. */
function Reminders() {
  const [st, reminders] = useScreen(() => app.reminders());
  if (!st) return null;
  const on = st.time !== null && st.time !== undefined;
  return (
    <Section title={s.reminders}>
      <p className="mb-4 text-sm text-subtle">{s.remindersHint}</p>
      <div className="grid gap-4">
        <div className="flex flex-wrap items-center gap-3">
          <label className="flex flex-1 items-center gap-3">
            <Switch checked={on} onCheckedChange={(v) => reminders.setTime(v ? 19 * 60 : null)} />
            <span className="text-sm font-medium">{s.remindAt}</span>
          </label>
          <Input
            type="time"
            aria-label={s.remindAt}
            className="w-32"
            disabled={!on}
            value={clock(st.time ?? 19 * 60)}
            onChange={(e) => {
              const minutes = minutesOf(e.target.value);
              if (minutes !== null) reminders.setTime(minutes);
            }}
          />
        </div>
        {on && (
          <>
            <label className="flex items-start gap-3">
              <Switch
                checked={st.here}
                disabled={!st.possible || st.busy}
                onCheckedChange={(v) => reminders.setHere(v)}
              />
              <span className="grid gap-0.5">
                <span className="text-sm font-medium">{s.remindHere}</span>
                {!st.possible && <span className="text-xs text-muted-foreground">{s.remindersImpossible}</span>}
                {st.blocked && <span className="text-xs text-muted-foreground">{s.remindersBlocked}</span>}
              </span>
            </label>
            <label className="flex items-center gap-3">
              <Switch checked={st.email} onCheckedChange={(v) => reminders.setEmail(v)} />
              <span className="text-sm font-medium">{s.remindByEmail}</span>
            </label>
          </>
        )}
      </div>
    </Section>
  );
}

export function Settings() {
  const [st, settings] = useScreen(() => app.settings());
  const issues = useApp()?.issues ?? 0;
  const [code, setCode] = useState("");
  if (!st) return <Loading />;
  const copy = (text: string) => (navigator.clipboard.writeText(text), toast(s.copied));
  return (
    <>
      <Header title={s.profile} />
      {issues > 0 && (
        <Link
          to="/profile/issues"
          className="mb-8 flex items-center gap-3 rounded-lg bg-warning px-4 py-3 text-sm hover:opacity-90"
        >
          <TriangleAlert className="size-4 shrink-0" />
          <span className="flex-1">{s.issues(issues)}</span>
          <ChevronRight className="size-4" />
        </Link>
      )}
      <Section title={s.account}>
        {st.signedIn ? (
          <div className="grid gap-4">
            <p className="text-subtle">{st.email}</p>
            <div className="flex flex-wrap items-center gap-3">
              <span className="min-w-0 flex-1">
                <span className="flex items-center gap-2 font-medium">
                  {st.name ?? `@${st.username}`} <Flag code={st.flag} />
                </span>
                {st.name && <span className="text-sm text-subtle">@{st.username}</span>}
              </span>
              <Button variant="outline" onClick={() => app.editProfile()}>
                {s.editProfile}
              </Button>
              {st.username && (
                <Button variant="ghost" asChild>
                  <Link to={`/u/${st.username}`}>{s.viewProfile}</Link>
                </Button>
              )}
            </div>
            <label className="flex items-start gap-3">
              <Switch checked={st.activityHidden} onCheckedChange={(v) => settings.setActivityHidden(v)} />
              <span className="grid gap-0.5">
                <span className="text-sm font-medium">{s.hideActivity}</span>
                <span className="text-xs text-muted-foreground">{s.hideActivityHint}</span>
              </span>
            </label>
            <div className="flex flex-wrap gap-2">
              <Button variant="secondary" onClick={() => settings.addPasskey()}>
                <KeyRound /> {s.addPasskey}
              </Button>
              <Button variant="secondary" asChild>
                <Link to="/profile/security">{s.security}</Link>
              </Button>
              <Button variant="ghost" onClick={signOut}>
                {s.signOut}
              </Button>
            </div>
          </div>
        ) : (
          <div className="grid justify-items-start gap-3">
            <p className="text-subtle">{s.withoutAccount}</p>
            <Button asChild>
              <Link to="/sign-in">{s.signIn}</Link>
            </Button>
          </div>
        )}
      </Section>

      {st.signedIn && (
        <Section
          title={s.subscription}
          action={
            <Button variant="ghost" size="sm" asChild>
              <Link to="/redeem">{s.redeem}</Link>
            </Button>
          }
        >
          {st.pro ? (
            <div className="grid justify-items-start gap-3">
              <p>{st.proUntil && (st.renews ? s.renewsOn(date(st.proUntil)) : s.proUntil(date(st.proUntil)))}</p>
              {st.provider === "stripe" && (
                <Button variant="secondary" onClick={() => settings.manage(go)}>
                  {s.manage}
                </Button>
              )}
            </div>
          ) : (
            <div className="grid justify-items-start gap-3">
              <p className="text-subtle">{s.proPitch}</p>
              <div className="flex gap-2">
                <Button onClick={() => settings.checkout("monthly", go)}>
                  {s.pro} · {s.monthly}
                </Button>
                <Button variant="secondary" onClick={() => settings.checkout("yearly", go)}>
                  {s.pro} · {s.yearly}
                </Button>
              </div>
            </div>
          )}
        </Section>
      )}

      {st.signedIn && <Reminders />}

      <Section title={s.preferences}>
        <div className="grid gap-5">
          <Choice
            label={s.grading}
            value={st.grading}
            options={[
              [4, s.fourButtons],
              [2, s.twoButtons],
            ]}
            onChange={(n) => settings.setGrading(n)}
          />
          <Choice
            label={s.theme}
            value={st.theme}
            options={[
              [0, s.system],
              [1, s.light],
              [2, s.dark],
            ]}
            onChange={(n) => settings.setTheme(n)}
          />
          <div className="grid gap-3">
            <Label>
              {s.textSize}: {st.textSize}%
            </Label>
            <Slider
              min={50}
              max={200}
              step={10}
              defaultValue={[st.textSize]}
              onValueCommit={([v]) => settings.setTextSize(v ?? 100)}
            />
          </div>
          <div className="flex gap-2">
            <Button variant="secondary" asChild>
              <Link to="/profile/templates">{s.templates}</Link>
            </Button>
            <Button variant="secondary" asChild>
              <Link to="/import">{s.importDeck}</Link>
            </Button>
          </div>
        </div>
      </Section>

      <Section title={s.agents}>
        <p className="mb-2 text-sm text-subtle">{s.agentsHint}</p>
        <div className="mb-4 flex gap-2">
          <Input readOnly value={st.mcpUrl} className="font-mono" />
          <Button variant="secondary" size="icon" aria-label={s.copyLink} onClick={() => copy(st.mcpUrl)}>
            <Copy />
          </Button>
        </div>
        <p className="mb-2 text-sm text-subtle">{s.localAgent}</p>
        <code className="mb-4 block rounded-md bg-muted px-3 py-2 font-mono text-sm">rondo-mcp sign-in</code>
        {st.signedIn &&
          (st.agents.length === 0 ? (
            <p className="text-sm text-muted-foreground">{s.noAgents}</p>
          ) : (
            st.agents.map((a) => (
              <div key={a.clientId} className="flex items-center gap-3 py-2">
                <span className="flex-1">{a.name}</span>
                <span className="text-xs text-muted-foreground">{s.connectedOn(date(a.since))}</span>
                <Button size="sm" variant="ghost" onClick={() => settings.revokeAgent(a.clientId)}>
                  {s.revoke}
                </Button>
              </div>
            ))
          ))}
      </Section>

      {st.signedIn && (
        <Section title={s.data}>
          <div className="grid justify-items-start gap-3">
            <Button variant="secondary" onClick={() => settings.export()}>
              {s.export}
            </Button>
            {st.confirming ? (
              <form className="flex gap-2" onSubmit={(e) => (e.preventDefault(), settings.confirmDelete(code))}>
                <Input
                  autoFocus
                  inputMode="numeric"
                  placeholder={s.code}
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                />
                <Button type="submit" variant="destructive">
                  {s.deleteAccount}
                </Button>
              </form>
            ) : (
              <Button
                variant="ghost"
                className="text-destructive"
                onClick={async () =>
                  (await confirm(`${s.deleteAccount}?`, s.deleteWarning)) && settings.deleteAccount()
                }
              >
                {s.deleteAccount}
              </Button>
            )}
            {st.confirming && (
              <p className="text-sm text-muted-foreground">
                {s.codeSent} {st.email}
              </p>
            )}
          </div>
        </Section>
      )}
    </>
  );
}
