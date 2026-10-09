import {
  Bell,
  Bot,
  Check,
  CircleUser,
  Copy,
  Download,
  Monitor,
  Moon,
  Shapes,
  ShieldCheck,
  SlidersHorizontal,
  Sparkles,
  Sun,
  TriangleAlert,
  UserRound,
} from "lucide-react";
import { useEffect, useState } from "react";
import { Link, Navigate, Outlet, useLocation, useOutletContext, useSearchParams } from "react-router";
import { ToggleGroup as ToggleGroupPrimitive } from "radix-ui";
import { toast } from "sonner";
import { Flag } from "@/components/Flag";
import {
  Avatar,
  confirm,
  Empty,
  Header,
  LearnMore,
  Loading,
  Row,
  Rows,
  Section,
  Spinner,
  Tabs,
} from "@/components/kit";
import { signOut } from "@/components/Shell";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Slider } from "@/components/ui/slider";
import { Switch } from "@/components/ui/switch";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { app, date, s, useApp, useScreen, type SettingsScreen, type SettingsState } from "@/rondo";
import { GRADE } from "@/screens/Study";
import { cn } from "cn";

const go = (url: string) => (location.href = url);

/** Settings' tabs, each an address of its own; the account's show once you're signed in. */
const tabs = [
  { to: "/settings", label: s.general, icon: SlidersHorizontal, end: true },
  { to: "/settings/account", label: s.account, icon: CircleUser },
  { to: "/settings/security", label: s.security, icon: ShieldCheck, account: true },
  { to: "/settings/plan", label: s.plan, icon: Sparkles, account: true },
  { to: "/settings/reminders", label: s.reminders, icon: Bell, account: true },
  { to: "/settings/assistants", label: s.agents, icon: Bot, account: true },
];

/** The screen every tab shares, opened once for all of them. */
const useSettings = () => useOutletContext<[SettingsState | null, SettingsScreen]>();

/** Settings: a tab for each part, under one header; opened afresh when someone signs in or out. */
export function Settings() {
  const signedIn = useApp()?.signedIn;
  return signedIn === undefined ? <Loading /> : <SettingsFor key={String(signedIn)} signedIn={signedIn} />;
}

function SettingsFor({ signedIn }: { signedIn: boolean }) {
  const settings = useScreen(() => app.settings());
  const { pathname, search } = useLocation();
  const [params] = useSearchParams();
  if (pathname.endsWith("/")) return <Navigate to={pathname.slice(0, -1) + search} replace />;
  // Stripe sends people back here once they've paid.
  if (params.has("paid") && pathname !== "/settings/plan") return <Navigate to="/settings/plan?paid=1" replace />;
  const locked = !signedIn && tabs.some((t) => t.account && t.to === pathname);
  return (
    <>
      <Header title={s.settings} />
      <Tabs label={s.settings} tabs={tabs.filter((t) => signedIn || !t.account)} />
      {locked ? <SignedOut /> : <Outlet context={settings} />}
    </>
  );
}

/** Without an account: what one adds, and a way to make one that comes back here. */
function SignedOut() {
  const { pathname } = useLocation();
  return (
    <Empty
      icon={UserRound}
      title={s.signInTitle}
      action={
        <Button asChild>
          <Link to={`/sign-in?next=${encodeURIComponent(pathname)}`}>{s.signIn}</Link>
        </Button>
      }
    >
      {s.signInHint} {s.withoutAccount}
    </Empty>
  );
}

const themes = [
  [0, s.system, Monitor],
  [1, s.light, Sun],
  [2, s.dark, Moon],
] as const;

const ANSWERS = ["", s.again, s.hard, s.good, s.easy];

/** Four answer buttons or two: the ratings each gives. */
const answerChoices = [
  [4, s.fourButtons, [1, 2, 3, 4]],
  [2, s.twoButtons, [1, 3]],
] as const;

/** Four answer buttons or two, each choice drawn as the buttons it gives, coloured as when studying. */
function AnswerButtons({ value, onChange }: { value: number; onChange: (buttons: number) => void }) {
  return (
    <ToggleGroupPrimitive.Root
      type="single"
      aria-label={s.grading}
      value={String(value)}
      onValueChange={(v) => v && onChange(+v)}
      className="grid gap-2 sm:grid-cols-2"
    >
      {answerChoices.map(([n, label, ratings]) => (
        <ToggleGroupPrimitive.Item
          key={n}
          value={String(n)}
          aria-label={label}
          className="flex items-center gap-1.5 rounded-lg border bg-card p-3 text-left outline-none hover:bg-accent/50 focus-visible:ring-[3px] focus-visible:ring-ring/50 data-[state=on]:border-primary data-[state=on]:ring-1 data-[state=on]:ring-primary"
        >
          {ratings.map((r) => (
            <span key={r} className={cn("rounded-md border bg-card px-2 py-1 text-xs font-medium", GRADE[r])}>
              {ANSWERS[r]}
            </span>
          ))}
          <Check className={cn("ml-auto size-4 text-primary", n !== value && "invisible")} />
        </ToggleGroupPrimitive.Item>
      ))}
    </ToggleGroupPrimitive.Root>
  );
}

/** How Rondo looks and studies, and the note types and imports behind your decks: no account needed. */
export function General() {
  const [st, settings] = useSettings();
  const [size, setSize] = useState<number | null>(null);
  const saved = st?.textSize;
  useEffect(() => setSize(null), [saved]);
  if (!st) return <Loading />;
  const textSize = size ?? st.textSize;
  return (
    <>
      <Section title={s.appearance}>
        <Rows>
          <Row
            title={s.theme}
            action={
              <ToggleGroup
                type="single"
                variant="outline"
                aria-label={s.theme}
                value={String(st.theme)}
                onValueChange={(v) => v && settings.setTheme(+v)}
              >
                {themes.map(([n, label, Icon]) => (
                  <ToggleGroupItem key={n} value={String(n)} className="px-3">
                    <Icon /> {label}
                  </ToggleGroupItem>
                ))}
              </ToggleGroup>
            }
          />
          <Row
            title={s.textSize}
            hint={s.textSizeHint}
            action={<span className="text-sm font-medium tabular-nums">{textSize}%</span>}
          >
            <div className="flex items-center gap-3">
              <span aria-hidden className="text-xs text-muted-foreground">
                A
              </span>
              <Slider
                aria-label={s.textSize}
                min={50}
                max={200}
                step={10}
                value={[textSize]}
                onValueChange={([v]) => v != null && setSize(v)}
                onValueCommit={([v]) => v != null && settings.setTextSize(v)}
              />
              <span aria-hidden className="text-xl text-muted-foreground">
                A
              </span>
            </div>
          </Row>
        </Rows>
      </Section>
      <Section title={s.studying}>
        <Rows>
          <Row title={s.grading} hint={s.gradingHint}>
            <AnswerButtons value={st.grading} onChange={(n) => settings.setGrading(n)} />
          </Row>
        </Rows>
      </Section>
      <Section title={s.library}>
        <Rows>
          <Row to="/settings/templates" icon={Shapes} title={s.templates} hint={s.templatesHint} />
          <Row to="/import" icon={Download} title={s.importDecks} hint={s.importDecksHint} />
        </Rows>
      </Section>
    </>
  );
}

/** Who you are to others, your data, and leaving: signing out, or deleting the account. */
export function Account() {
  const [st, settings] = useSettings();
  const me = useApp();
  const [code, setCode] = useState("");
  if (!me?.signedIn) return <SignedOut />;
  if (!st) return <Loading />;
  const name = me.name?.trim() || (me.username ? `@${me.username}` : (me.email ?? s.account));
  return (
    <>
      <section className="mb-10 flex flex-wrap items-center gap-x-4 gap-y-3 rounded-lg bg-card p-4 shadow-sm">
        <Avatar name={me.name?.trim() || me.email || s.account} className="size-12 text-base" />
        <div className="min-w-0 flex-[1_1_12rem]">
          <div className="flex items-center gap-2 text-lg font-medium">
            <span className="truncate">{name}</span> <Flag code={me.flag} />
          </div>
          <div className="truncate text-sm text-subtle">
            {me.name?.trim() && me.username && `@${me.username} · `}
            {me.email}
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          {me.username && (
            <Button variant="ghost" asChild>
              <Link to={`/u/${me.username}`}>{s.viewProfile}</Link>
            </Button>
          )}
          <Button variant="outline" onClick={() => app.editProfile()}>
            {s.editProfile}
          </Button>
        </div>
      </section>
      <Section title={s.privacySettings} action={<LearnMore page="profiles" />}>
        <Rows>
          <Row
            label
            title={s.hideActivity}
            hint={s.hideActivityHint}
            action={<Switch checked={st.activityHidden} onCheckedChange={(v) => settings.setActivityHidden(v)} />}
          />
        </Rows>
      </Section>
      <Section title={s.data}>
        <Rows>
          <Row
            title={s.export}
            hint={s.exportHint}
            action={
              <Button variant="outline" disabled={!!st.busy} onClick={() => settings.export()}>
                {st.busy === "export" && <Spinner />} {s.exportAction}
              </Button>
            }
          />
          {me.issues > 0 && (
            <Row to="/settings/issues" icon={TriangleAlert} title={s.syncIssues} hint={s.issues(me.issues)} />
          )}
          <Row
            title={s.signOut}
            hint={s.signOutHint}
            action={
              <Button variant="outline" onClick={signOut}>
                {s.signOut}
              </Button>
            }
          />
        </Rows>
      </Section>
      <Section title={s.dangerZone}>
        <Rows className="ring-1 ring-destructive/30">
          <Row
            title={s.deleteAccount}
            hint={s.deleteWarning}
            action={
              !st.confirming && (
                <Button
                  variant="outline"
                  className="text-destructive hover:text-destructive"
                  disabled={!!st.busy}
                  onClick={async () =>
                    (await confirm(`${s.deleteAccount}?`, s.deleteWarning)) && settings.deleteAccount()
                  }
                >
                  {st.busy === "delete" && <Spinner />} {s.deleteAccount}
                </Button>
              )
            }
          >
            {st.confirming && (
              <form
                className="grid gap-2"
                onSubmit={(e) => (e.preventDefault(), code.trim() && settings.confirmDelete(code))}
              >
                <p className="text-sm text-subtle">
                  {s.codeSent} {st.email}
                </p>
                <div className="flex max-w-sm gap-2">
                  <Input
                    autoFocus
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    aria-label={s.code}
                    placeholder={s.code}
                    value={code}
                    onChange={(e) => setCode(e.target.value)}
                  />
                  <Button type="submit" variant="destructive" disabled={!!st.busy || !code.trim()}>
                    {st.busy === "delete" && <Spinner />} {s.deleteAccount}
                  </Button>
                </div>
              </form>
            )}
          </Row>
        </Rows>
      </Section>
    </>
  );
}

const PRO = [s.proEditors, s.proAgents, s.proStorage];

/** Free or Pro: what Pro adds, upgrading or managing it, and redeeming a code. */
export function Plan() {
  const [st, settings] = useSettings();
  const [params, setParams] = useSearchParams();
  // Back from paying, the checkout stays under way until Pro arrives; the server's notification
  // welcomes it. The address forgets it was here.
  const [paid] = useState(() => params.has("paid"));
  useEffect(() => {
    if (params.has("paid")) setParams({}, { replace: true });
  }, [params, setParams]);
  const ready = st !== null;
  useEffect(() => void (paid && ready && settings.awaitPro()), [paid, ready, settings]);
  const [period, setPeriod] = useState("monthly");
  const [code, setCode] = useState("");
  // A redeemed code moves Pro's end: it's done with.
  const until = st?.proUntil;
  useEffect(() => setCode(""), [until]);
  if (!st) return <Loading />;
  const source = s.proSource(st.provider);
  const ends = st.proUntil && (st.renews ? s.renewsOn(date(st.proUntil)) : s.proUntil(date(st.proUntil)));
  return (
    <>
      <Section title={s.plan} action={<LearnMore page="plans" />}>
        <div className="grid gap-3">
          {!st.pro && (
            <div className="rounded-lg bg-card p-4 shadow-sm">
              <div className="flex items-center gap-2">
                <span className="font-medium">{s.free}</span>
                <Badge variant="secondary">{s.currentPlan}</Badge>
              </div>
              <p className="mt-1 text-sm text-subtle">{s.freePlan}</p>
            </div>
          )}
          <div className={cn("rounded-lg bg-card p-4 shadow-sm", !st.pro && "ring-1 ring-primary/50")}>
            <div className="flex flex-wrap items-center gap-x-2 gap-y-3">
              <Sparkles className="size-4 text-primary" />
              <span className="font-medium">{s.pro}</span>
              {st.pro && <Badge variant="secondary">{s.currentPlan}</Badge>}
              {st.pro && st.provider === "stripe" && (
                <Button variant="outline" className="ml-auto" disabled={!!st.busy} onClick={() => settings.manage(go)}>
                  {st.busy === "portal" && <Spinner />} {s.manage}
                </Button>
              )}
            </div>
            {st.pro && (ends || source) && (
              <p className="mt-1 text-sm text-subtle">{[ends, source].filter(Boolean).join(". ")}</p>
            )}
            <ul className="mt-4 grid gap-2 text-sm">
              {PRO.map((f) => (
                <li key={f} className="flex gap-2">
                  <Check className="mt-0.5 size-4 shrink-0 text-primary" /> {f}
                </li>
              ))}
            </ul>
            {!st.pro && (
              <div className="mt-5 flex flex-wrap items-center justify-between gap-3 border-t pt-4">
                <ToggleGroup
                  type="single"
                  variant="outline"
                  aria-label={s.payEvery}
                  value={period}
                  onValueChange={(v) => v && setPeriod(v)}
                >
                  <ToggleGroupItem value="monthly" className="px-3">
                    {s.monthly}
                  </ToggleGroupItem>
                  <ToggleGroupItem value="yearly" className="px-3">
                    {s.yearly}
                  </ToggleGroupItem>
                </ToggleGroup>
                <Button disabled={!!st.busy} onClick={() => settings.checkout(period, go)}>
                  {st.busy === "checkout" ? <Spinner /> : <Sparkles />} {s.upgrade}
                </Button>
              </div>
            )}
          </div>
        </div>
      </Section>
      {!(st.pro && st.renews) && (
        <Section>
          <Rows>
            <Row title={s.redeemTitle} hint={s.redeemHint}>
              <form
                className="flex max-w-md gap-2"
                onSubmit={(e) => (e.preventDefault(), code.trim() && settings.redeem(code))}
              >
                <Input
                  aria-label={s.codeLabel}
                  placeholder="XXXX-XXXX-XXXX"
                  autoComplete="off"
                  className="font-mono uppercase"
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                />
                <Button type="submit" variant="outline" disabled={!!st.busy || !code.trim()}>
                  {st.busy === "redeem" && <Spinner />} {s.redeemAction}
                </Button>
              </form>
            </Row>
          </Rows>
        </Section>
      )}
    </>
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
export function Reminders() {
  const [st, reminders] = useScreen(() => app.reminders());
  if (!st) return <Loading />;
  const on = st.time !== null && st.time !== undefined;
  return (
    <Section title={s.reminders} action={<LearnMore page="reminders" />}>
      <Rows>
        <Row
          label
          title={s.dailyReminder}
          hint={s.remindersHint}
          action={<Switch checked={on} onCheckedChange={(v) => reminders.setTime(v ? 19 * 60 : null)} />}
        />
        {on && (
          <>
            <Row
              label
              title={s.remindAt}
              action={
                <Input
                  type="time"
                  className="w-32"
                  value={clock(st.time ?? 19 * 60)}
                  onChange={(e) => {
                    const minutes = minutesOf(e.target.value);
                    if (minutes !== null) reminders.setTime(minutes);
                  }}
                />
              }
            />
            <Row
              label
              title={s.remindHere}
              hint={!st.possible ? s.remindersImpossible : st.blocked ? s.remindersBlocked : undefined}
              action={
                <Switch
                  checked={st.here}
                  disabled={!st.possible || st.busy}
                  onCheckedChange={(v) => reminders.setHere(v)}
                />
              }
            />
            <Row
              label
              title={s.remindByEmail}
              action={<Switch checked={st.email} onCheckedChange={(v) => reminders.setEmail(v)} />}
            />
          </>
        )}
      </Rows>
    </Section>
  );
}

const copy = (text: string) => (navigator.clipboard.writeText(text), toast(s.copied));

/** Text to copy, with a button that copies it: a server's address, a command. */
const Copyable = ({ text, label }: { text: string; label: string }) => (
  <div className="flex gap-2">
    <code className="flex h-9 min-w-0 flex-1 items-center overflow-x-auto rounded-md bg-muted px-3 font-mono text-sm whitespace-nowrap">
      {text}
    </code>
    <Button variant="outline" size="icon" aria-label={label} onClick={() => copy(text)}>
      <Copy />
    </Button>
  </div>
);

/** AI assistants: connecting them through Rondo (Pro) or on your computer, and the ones connected. */
export function Assistants() {
  const [st, settings] = useSettings();
  if (!st) return <Loading />;
  return (
    <>
      <Section title={s.throughRondo} action={<LearnMore page="agents" />}>
        <Rows>
          <Row
            title={s.serverUrl}
            hint={s.serverUrlHint}
            action={!st.pro && <Badge variant="secondary">{s.pro}</Badge>}
          >
            <Copyable text={st.mcpUrl} label={s.copyLink} />
            {!st.pro && (
              <p className="text-sm text-subtle">
                {s.agentsNeedPro}{" "}
                <Link to="/settings/plan" className="font-medium text-link hover:underline">
                  {s.upgrade}
                </Link>
              </p>
            )}
          </Row>
        </Rows>
      </Section>
      <Section title={s.connected}>
        <Rows>
          {st.agents.length === 0 ? (
            <p className="px-4 py-3.5 text-sm text-subtle">{s.noAgents}</p>
          ) : (
            st.agents.map((a) => (
              <Row
                key={a.clientId}
                icon={Bot}
                title={a.name}
                hint={s.connectedOn(date(a.since))}
                action={
                  <Button
                    variant="outline"
                    disabled={!!st.busy}
                    onClick={async () =>
                      (await confirm(s.disconnectTitle(a.name), s.disconnectHint, s.disconnect)) &&
                      settings.revokeAgent(a.clientId)
                    }
                  >
                    {s.disconnect}
                  </Button>
                }
              />
            ))
          )}
        </Rows>
      </Section>
      <Section title={s.onYourComputer}>
        <Rows>
          <Row title="rondo-mcp" hint={s.localAgent}>
            <Copyable text="rondo-mcp sign-in" label={s.copy} />
          </Row>
        </Rows>
      </Section>
    </>
  );
}
