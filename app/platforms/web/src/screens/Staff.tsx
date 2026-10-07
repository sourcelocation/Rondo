import { Copy, Gavel, Inbox, Link as LinkIcon, ScrollText, SearchX, Shield, Ticket, Undo2 } from "lucide-react";
import { useState, type ReactNode } from "react";
import { Link, NavLink, useNavigate, useParams } from "react-router";
import { toast } from "sonner";
import { ask, confirm, Empty, Header, Loading, Section } from "@/components/kit";
import { ModerateDialog, RemoveDeckDialog } from "@/components/ModerateDialog";
import { Checkbox } from "@/components/ui/checkbox";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { app, date, s, useApp, useScreen, type CaseItemView, type LogItem, type PersonRef } from "@/rondo";
import { cn } from "cn";

/** Staff pages that aren't public pages with extra buttons: the few things only staff look at. */
const tabs = [
  { to: "/staff/cases", label: s.cases, permission: "cases.review" },
  { to: "/staff/search", label: s.search, permission: "users.search" },
  { to: "/staff/log", label: s.log, permission: null },
  { to: "/staff/team", label: s.team, permission: null },
  { to: "/staff/promo", label: s.promo, permission: "promo.manage" },
];

/** Every staff page: its tabs, and a way to confirm with a passkey when the tools are locked. */
export function StaffFrame({ title, children }: { title: string; children: ReactNode }) {
  const st = useApp();
  const shown = tabs.filter((t) => !t.permission || st?.permissions.includes(t.permission) || st?.staffLocked);
  return (
    <>
      <Header title={title} />
      <nav className="mb-8 flex gap-1 border-b">
        {shown.map((t) => (
          <NavLink
            key={t.to}
            to={t.to}
            className={({ isActive }) =>
              cn(
                "-mb-px border-b-2 px-3 py-2 text-sm font-medium",
                isActive ? "border-foreground" : "border-transparent text-subtle hover:text-foreground",
              )
            }
          >
            {t.label}
          </NavLink>
        ))}
      </nav>
      {st?.staffLocked ? <PasskeyNeeded /> : children}
    </>
  );
}

/** Staff tools stay locked until this session signs in with a passkey. */
export const PasskeyNeeded = () => (
  <Empty icon={Shield} action={<Button onClick={() => app.confirmPasskey()}>{s.confirmPasskey}</Button>}>
    {s.staffLocked}
  </Empty>
);

const Person = ({ who }: { who: PersonRef | null | undefined }) =>
  who ? (
    who.username ? (
      <Link to={`/u/${who.username}`} className="font-medium hover:underline">
        {who.label}
      </Link>
    ) : (
      <span className="font-medium">{who.label}</span>
    )
  ) : (
    <span className="font-medium">{s.rondoItself}</span>
  );

const copyLink = (id: string) => (
  navigator.clipboard.writeText(`${location.origin}/app/staff/log/${id}`),
  toast(s.copied)
);

/** One entry of the log: what was done, by whom, why, and whether it was undone. */
export function Entry({ item, onUndo }: { item: LogItem; onUndo: (reason: string) => void }) {
  return (
    <li className={cn("grid gap-1 rounded-lg border p-4", item.undone && "opacity-60")}>
      <div className="flex flex-wrap items-baseline gap-x-2">
        <Link to={`/staff/log/${item.id}`} className={cn("font-medium hover:underline", item.undone && "line-through")}>
          {item.summary}
        </Link>
        {item.undone && <Badge variant="secondary">{s.undone}</Badge>}
      </div>
      <div className="text-sm text-subtle">
        <Person who={item.actor} /> · {date(item.at)}
        {item.until && <> · {s.until(date(item.until))}</>}
      </div>
      {(item.reason || item.note) && (
        <p className="text-sm">
          {item.reason && <span className="font-medium">{item.reason}</span>}
          {item.reason && item.note && ": "}
          {item.note}
        </p>
      )}
      {item.undone && item.undoneBy && (
        <p className="text-sm text-subtle">{s.undoneBy(item.undoneBy.label, item.undoReason)}</p>
      )}
      <div className="mt-1 flex gap-1">
        {item.undoable && (
          <Button
            size="sm"
            variant="outline"
            onClick={async () => {
              const why = await ask(s.undoReason);
              if (why) onUndo(why);
            }}
          >
            <Undo2 /> {s.undo}
          </Button>
        )}
        <Button size="sm" variant="ghost" onClick={() => copyLink(item.id)}>
          <LinkIcon /> {s.copyLink}
        </Button>
      </div>
    </li>
  );
}

/** The log, or the part of it about one person or deck. */
export function LogList({ actor, user, deck }: { actor?: string; user?: string; deck?: string }) {
  const [st, log] = useScreen(() => app.staffLog(actor ?? null, user ?? null, deck ?? null), [actor, user, deck]);
  if (!st) return <Loading />;
  if (st.items.length === 0) return <Empty icon={ScrollText}>{s.nothingLogged}</Empty>;
  return (
    <>
      <ul className="grid gap-2">
        {st.items.map((item) => (
          <Entry key={item.id} item={item} onUndo={(why) => log.undo(item.id, why)} />
        ))}
      </ul>
      {st.more && (
        <Button variant="ghost" className="mt-4 w-full" onClick={() => log.more()}>
          {s.showMore}
        </Button>
      )}
    </>
  );
}

export const StaffLog = () => (
  <StaffFrame title={s.staff}>
    <LogList />
  </StaffFrame>
);

/** One entry, at an address staff can send each other. */
export function StaffEntry() {
  const { id } = useParams();
  const [st, entry] = useScreen(() => app.staffAction(id!), [id]);
  return (
    <StaffFrame title={s.staff}>
      {st ? (
        <ul>
          <Entry item={st} onUndo={(why) => entry.undo(why)} />
        </ul>
      ) : (
        <Loading />
      )}
    </StaffFrame>
  );
}

/** Finding people: by name for everyone on staff, by email or address for those who may. */
export function StaffSearch() {
  const [st, search] = useScreen(() => app.staffSearch());
  const permissions = useApp()?.permissions ?? [];
  const [query, setQuery] = useState("");
  const [by, setBy] = useState("name");
  const ways = [
    { by: "name", label: s.byName, hint: s.searchPeople, permission: "users.search" },
    { by: "email", label: s.byEmail, hint: s.searchEmail, permission: "users.email" },
    { by: "ip", label: s.byAddress, hint: s.searchAddress, permission: "users.network" },
  ].filter((w) => permissions.includes(w.permission));
  const way = ways.find((w) => w.by === by) ?? ways[0];
  return (
    <StaffFrame title={s.staff}>
      <form
        className="mb-6 flex flex-col gap-2 sm:flex-row"
        onSubmit={(e) => (e.preventDefault(), search.search(query, by))}
      >
        <Input
          type="search"
          autoFocus
          placeholder={way?.hint}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="flex-1"
        />
        {ways.length > 1 && (
          <ToggleGroup type="single" variant="outline" value={by} onValueChange={(v) => v && setBy(v)}>
            {ways.map((w) => (
              <ToggleGroupItem key={w.by} value={w.by} className="px-3">
                {w.label}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
        )}
        <Button type="submit">{s.search}</Button>
      </form>
      {st && st.query && st.items.length === 0 && <Empty icon={SearchX}>{s.nobodyFound}</Empty>}
      <ul className="grid gap-1">
        {st?.items.map((p) => (
          <li key={p.person.id} className="flex flex-wrap items-center gap-3 rounded-lg px-3 py-2 hover:bg-accent/50">
            <span className="min-w-0 flex-1">
              <Person who={p.person} />
              <span className="ml-2 text-sm text-subtle">{s.memberSince(date(p.joined))}</span>
            </span>
            {p.roles.map((r) => (
              <Badge key={r} variant="secondary">
                {s.roleName(r)}
              </Badge>
            ))}
            {p.restrictedUntil && <Badge variant="destructive">{s.restrict}</Badge>}
            {p.bannedUntil && <Badge variant="destructive">{s.ban}</Badge>}
          </li>
        ))}
      </ul>
    </StaffFrame>
  );
}

const lanes = ["illegal", "safety", "spam", "other", "waves", "flags"];

/** What a case is about, in one line. */
const About = ({ c }: { c: CaseItemView }) =>
  c.deck ? (
    <span>
      {c.deck.slug ? (
        <Link to={`/d/${c.deck.slug}`} className="font-medium hover:underline">
          “{c.deck.name}”
        </Link>
      ) : (
        <span className="font-medium">“{c.deck.name}”</span>
      )}
      {c.deck.owner && (
        <>
          {" · "}
          <Person who={c.deck.owner} />
        </>
      )}
    </span>
  ) : c.person ? (
    <Person who={c.person} />
  ) : (
    <span className="font-medium">{c.summary}</span>
  );

/** The queue: open cases, the most serious lanes first. Nothing below the threshold shows here. */
export function Cases() {
  const [st, cases] = useScreen(() => app.cases());
  return (
    <StaffFrame title={s.staff}>
      <ToggleGroup
        type="single"
        variant="outline"
        className="mb-6 flex-wrap justify-start"
        value={st?.lane ?? "all"}
        onValueChange={(v) => v && cases.show(v === "all" ? null : v)}
      >
        <ToggleGroupItem value="all" className="px-3">
          {s.allLanes}
        </ToggleGroupItem>
        {lanes.map((l) => (
          <ToggleGroupItem key={l} value={l} className="px-3">
            {s.lane(l)}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
      {!st ? (
        <Loading />
      ) : st.items.length === 0 ? (
        <Empty icon={Inbox}>{s.noCases}</Empty>
      ) : (
        <ul className="grid gap-2">
          {st.items.map((c) => (
            <li key={c.id}>
              <Link to={`/staff/cases/${c.id}`} className="grid gap-1 rounded-lg border p-4 hover:bg-accent/50">
                <span className="flex flex-wrap items-center gap-2">
                  <Badge variant={c.lane === "illegal" ? "destructive" : "secondary"}>{s.lane(c.lane)}</Badge>
                  <About c={c} />
                </span>
                <span className="text-sm text-subtle">
                  {c.kind === "reports" ? `${s.reportCount(c.reports)} · ${c.reasons.join(", ")}` : c.summary} ·{" "}
                  {date(c.opened)}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </StaffFrame>
  );
}

/** A list of decks or people in a wave, to select and act on together. */
function Pickable<T extends { id: string }>({
  items,
  render,
  picked,
  onPick,
}: {
  items: T[];
  render: (item: T) => ReactNode;
  picked: string[];
  onPick: (ids: string[]) => void;
}) {
  return (
    <ul className="grid gap-1">
      <li>
        <label className="flex items-center gap-3 px-3 py-1 text-sm text-subtle">
          <Checkbox
            checked={items.length > 0 && picked.length === items.length}
            onCheckedChange={(v) => onPick(v === true ? items.map((i) => i.id) : [])}
          />
          {s.selectAll}
        </label>
      </li>
      {items.map((i) => (
        <li key={i.id}>
          <label className="flex items-center gap-3 rounded-lg px-3 py-2 hover:bg-accent/50">
            <Checkbox
              checked={picked.includes(i.id)}
              onCheckedChange={(v) => onPick(v === true ? [...picked, i.id] : picked.filter((p) => p !== i.id))}
            />
            {render(i)}
          </label>
        </li>
      ))}
    </ul>
  );
}

/** One case: its reports (or what's behind a wave), and acting on it or dismissing it. */
export function CasePage() {
  const { id } = useParams();
  const [st, one] = useScreen(() => app.case(id!), [id]);
  const permissions = useApp()?.permissions ?? [];
  const [decks, setDecks] = useState<string[]>([]);
  const [people, setPeople] = useState<string[]>([]);
  if (!st)
    return (
      <StaffFrame title={s.staff}>
        <Loading />
      </StaffFrame>
    );
  const c = st.item;
  const kinds = [
    ["users.warn", "user.warn"],
    ["users.restrict", "user.restrict"],
    ["users.ban", "user.ban"],
  ]
    .filter(([p]) => permissions.includes(p!))
    .map(([, k]) => k!);
  const targetDecks = c.deck ? [c.deck.id] : decks;
  const targetPeople = c.person ? [c.person.id] : people;
  return (
    <StaffFrame title={s.staff}>
      <div className="mb-6 grid gap-2">
        <span className="flex flex-wrap items-center gap-2">
          <Badge variant={c.lane === "illegal" ? "destructive" : "secondary"}>{s.lane(c.lane)}</Badge>
          <About c={c} />
          {c.resolved && <Badge variant="secondary">{s.resolved}</Badge>}
        </span>
        {c.summary && c.kind !== "reports" && <p className="text-sm text-subtle">{c.summary}</p>}
      </div>
      {!c.resolved && (
        <div className="mb-8 flex flex-wrap gap-2">
          {targetDecks.length > 0 && permissions.includes("decks.remove") && (
            <RemoveDeckDialog
              onSubmit={(reason, note, takeDown) => one.removeDecks(targetDecks, reason, note, takeDown)}
            >
              <Button variant="outline">{s.removeFromDiscover}</Button>
            </RemoveDeckDialog>
          )}
          {targetPeople.length > 0 && kinds.length > 0 && (
            <ModerateDialog
              kinds={kinds}
              suggestedKind={kinds[0]!}
              suggestedDays={null}
              onSubmit={(kind, reason, note, days) => one.moderate(targetPeople, kind, reason, note, days)}
            >
              <Button variant="outline">
                <Gavel /> {s.moderate}
              </Button>
            </ModerateDialog>
          )}
          <Button
            variant="ghost"
            onClick={async () => (await confirm(`${s.dismiss}?`, s.dismissHint, s.dismiss, false)) && one.dismiss()}
          >
            {s.dismiss}
          </Button>
        </div>
      )}
      {st.reports.length > 0 && (
        <Section title={s.reportsLabel}>
          <ul className="grid gap-2">
            {st.reports.map((r, i) => (
              <li key={i} className="grid gap-1 rounded-lg border p-4">
                <span className="flex flex-wrap items-baseline gap-x-2">
                  <span className="font-medium">{r.reason}</span>
                  {r.law && <span className="text-sm">· {r.law}</span>}
                </span>
                {r.note && <p className="text-sm whitespace-pre-line">{r.note}</p>}
                <span className="text-sm text-subtle">
                  <Person who={r.reporter} /> · {date(r.at)} · {s.weightLabel} {r.weight.toFixed(2)}
                </span>
              </li>
            ))}
          </ul>
        </Section>
      )}
      {st.decks.length > 0 && (
        <Section title={s.decks}>
          <Pickable
            items={st.decks}
            picked={decks}
            onPick={setDecks}
            render={(d) => (
              <span>
                “{d.name}”{d.owner && <> · {d.owner.label}</>}
              </span>
            )}
          />
        </Section>
      )}
      {st.people.length > 0 && (
        <Section title={s.people}>
          <Pickable items={st.people} picked={people} onPick={setPeople} render={(p) => <span>{p.label}</span>} />
        </Section>
      )}
      {st.reporters.length > 0 && (
        <Section title={s.reporters}>
          <Pickable items={st.reporters} picked={people} onPick={setPeople} render={(p) => <span>{p.label}</span>} />
        </Section>
      )}
    </StaffFrame>
  );
}

/** Promo codes: the batches so far, and making more. */
export function Promo() {
  const [st, promo] = useScreen(() => app.promo());
  const navigate = useNavigate();
  const [count, setCount] = useState("10");
  const [days, setDays] = useState("30");
  const [usable, setUsable] = useState("0");
  const [note, setNote] = useState("");
  return (
    <StaffFrame title={s.staff}>
      <Section title={s.newBatch}>
        <form
          className="grid gap-3 sm:grid-cols-[repeat(3,minmax(0,8rem))_1fr_auto] sm:items-end"
          onSubmit={(e) => (
            e.preventDefault(),
            promo.create(+count, +days, +usable, note, (id) => navigate(`/staff/promo/${id}`))
          )}
        >
          <label className="grid gap-1 text-sm">
            {s.howMany}
            <Input type="number" min={1} max={500} value={count} onChange={(e) => setCount(e.target.value)} />
          </label>
          <label className="grid gap-1 text-sm">
            {s.daysOfPro}
            <Input type="number" min={1} max={9999} value={days} onChange={(e) => setDays(e.target.value)} />
          </label>
          <label className="grid gap-1 text-sm">
            {s.usableFor}
            <Input type="number" min={0} value={usable} onChange={(e) => setUsable(e.target.value)} />
          </label>
          <label className="grid gap-1 text-sm">
            {s.batchNote}
            <Input value={note} onChange={(e) => setNote(e.target.value)} />
          </label>
          <Button type="submit">{s.newBatch}</Button>
        </form>
      </Section>
      {!st ? (
        <Loading />
      ) : st.batches.length === 0 ? (
        <Empty icon={Ticket}>{s.noBatches}</Empty>
      ) : (
        <ul className="grid gap-1">
          {st.batches.map((b) => (
            <li key={b.id}>
              <Link
                to={`/staff/promo/${b.id}`}
                className={cn(
                  "flex flex-wrap items-center gap-3 rounded-lg px-3 py-2 hover:bg-accent/50",
                  b.undone && "opacity-60",
                )}
              >
                <span className="min-w-0 flex-1">
                  <span className="font-medium">{b.note ?? s.promo}</span>{" "}
                  <span className="text-sm text-subtle">
                    · {s.forDays(b.days)} · {date(b.created)}
                  </span>
                </span>
                <span className="text-sm text-subtle">{s.usedCodes(b.used, b.codes)}</span>
                {b.undone && <Badge variant="secondary">{s.undone}</Badge>}
              </Link>
            </li>
          ))}
        </ul>
      )}
    </StaffFrame>
  );
}

/** One batch: its codes, whether each was used and by whom. */
export function Batch() {
  const { id } = useParams();
  const [st, batch] = useScreen(() => app.batch(id!), [id]);
  return (
    <StaffFrame title={s.staff}>
      {!st ? (
        <Loading />
      ) : (
        <>
          <div className="mb-6 flex flex-wrap items-center gap-3">
            <span className="min-w-0 flex-1">
              <span className="font-medium">{st.batch.note ?? s.promo}</span>{" "}
              <span className="text-sm text-subtle">
                · {s.forDays(st.batch.days)} · {s.usedCodes(st.batch.used, st.batch.codes)}
                {st.batch.redeemBy && <> · {s.until(date(st.batch.redeemBy))}</>}
              </span>
            </span>
            <Button
              variant="outline"
              disabled={!st.unused}
              onClick={() => (navigator.clipboard.writeText(st.unused), toast(s.copied))}
            >
              <Copy /> {s.copyUnused}
            </Button>
          </div>
          <ul className="grid gap-1">
            {st.codes.map((c) => (
              <li key={c.code} className="flex flex-wrap items-center gap-3 rounded-lg px-3 py-2 hover:bg-accent/50">
                <span className={cn("font-mono", c.revoked && "line-through opacity-60")}>{c.code}</span>
                <span className="min-w-0 flex-1 text-sm text-subtle">
                  {c.revoked ? (
                    s.revoked
                  ) : c.usedBy ? (
                    <>
                      <Person who={c.usedBy} /> · {c.usedAt && date(c.usedAt)}
                    </>
                  ) : (
                    s.unused
                  )}
                </span>
                {!c.revoked && (
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={async () =>
                      (await confirm(`${s.revoke} ${c.code}?`, undefined, s.revoke)) && batch.revoke(c.code)
                    }
                  >
                    {s.revoke}
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </>
      )}
    </StaffFrame>
  );
}

export function Team() {
  const [st, team] = useScreen(() => app.team());
  return (
    <StaffFrame title={s.staff}>
      {!st ? (
        <Loading />
      ) : (
        <Section title={s.team}>
          <ul className="grid gap-1">
            {st.members.map((m) => (
              <li
                key={m.person.id}
                className="flex flex-wrap items-center gap-3 rounded-lg px-3 py-2 hover:bg-accent/50"
              >
                <span className="min-w-0 flex-1">
                  <Person who={m.person} />
                </span>
                {m.roles.map((r) => (
                  <Badge key={r} variant="secondary">
                    {s.roleName(r)}
                  </Badge>
                ))}
                {m.removable.map((r) => (
                  <Button
                    key={r}
                    size="sm"
                    variant="ghost"
                    onClick={async () =>
                      (await confirm(`${s.removeRole}: ${s.roleName(r)}?`, m.person.label, s.removeRole)) &&
                      team.revoke(m.person.id, r)
                    }
                  >
                    {s.removeRole}
                  </Button>
                ))}
              </li>
            ))}
          </ul>
        </Section>
      )}
    </StaffFrame>
  );
}
