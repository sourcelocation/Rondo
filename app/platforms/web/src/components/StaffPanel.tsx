import { Gavel, ShieldOff } from "lucide-react";
import { Link } from "react-router";
import { ask, confirm, Loading, Section } from "@/components/kit";
import { ModerateDialog, RemoveDeckDialog } from "@/components/ModerateDialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { app, date, s, useApp, useScreen, type PreviewScreen, type StaffUserState } from "@/rondo";
import { LogList, PasskeyNeeded } from "@/screens/Staff";

/** Staff tools show for staff who haven't hidden them, with the permission at hand. */
export function useStaff(permission: string) {
  const st = useApp();
  if (!st?.staff || st.roles.length === 0) return { show: false, locked: false, can: () => false };
  return {
    show: st.staffLocked || st.permissions.includes(permission),
    locked: st.staffLocked,
    can: (p: string) => st.permissions.includes(p),
  };
}

const kindsFor = (can: (p: string) => boolean) =>
  [
    ["users.warn", "user.warn"],
    ["users.restrict", "user.restrict"],
    ["users.ban", "user.ban"],
    ["users.restrict", "user.lift"],
    ["users.rename", "user.rename"],
  ]
    .filter(([p]) => can(p!))
    .map(([, k]) => k!);

const Row = ({ label, children }: { label: string; children: React.ReactNode }) => (
  <div className="grid gap-1 sm:grid-cols-[10rem_1fr]">
    <dt className="text-sm text-subtle">{label}</dt>
    <dd className="min-w-0 text-sm">{children}</dd>
  </div>
);

const until = (iso: string | null) => (iso && new Date(iso).getFullYear() < 9000 ? date(iso) : null);

/** What staff see about a person, beside their public profile. */
export function PersonPanel({ userId }: { userId: string }) {
  const staff = useStaff("users.search");
  if (!staff.show) return null;
  return (
    <Section title={s.moderation}>
      {staff.locked ? <PasskeyNeeded /> : <PersonTools userId={userId} can={staff.can} />}
    </Section>
  );
}

function PersonTools({ userId, can }: { userId: string; can: (p: string) => boolean }) {
  const [st, person] = useScreen(() => app.staffUser(userId), [userId]);
  if (!st) return <Loading />;
  const kinds = kindsFor(can);
  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-center gap-2">
        {st.roles.map((r) => (
          <Badge key={r}>{s.roleName(r)}</Badge>
        ))}
        {st.restrictedUntil && <Badge variant="destructive">{s.restrictedUntil(until(st.restrictedUntil))}</Badge>}
        {st.bannedUntil && <Badge variant="destructive">{s.bannedUntil(until(st.bannedUntil))}</Badge>}
        <span className="text-sm text-subtle">{s.recentSteps(st.recent)}</span>
      </div>
      <div className="flex flex-wrap gap-2">
        {kinds.length > 0 && (
          <ModerateDialog
            kinds={kinds}
            suggestedKind={st.suggestedKind}
            suggestedDays={st.suggestedDays ?? null}
            onSubmit={(kind, reason, note, days) => person.moderate(kind, reason, note, days)}
          >
            <Button variant="outline">
              <Gavel /> {s.moderate}
            </Button>
          </ModerateDialog>
        )}
        {can("pro.grant") && (
          <Button
            variant="ghost"
            onClick={async () => {
              const days = await ask(`${s.grantPro}: ${s.daysOfPro}`, "30");
              if (days && +days > 0) person.grantPro(+days);
            }}
          >
            {s.grantPro}
          </Button>
        )}
        {st.grantable.map((r) => (
          <Button
            key={r}
            variant="ghost"
            onClick={async () =>
              (await confirm(`${s.grantRole}: ${s.roleName(r)}?`, st.person.label, s.grantRole, false)) &&
              person.grant(r)
            }
          >
            {s.grantRole}: {s.roleName(r)}
          </Button>
        ))}
        {st.removable.map((r) => (
          <Button
            key={r}
            variant="ghost"
            onClick={async () =>
              (await confirm(`${s.removeRole}: ${s.roleName(r)}?`, st.person.label, s.removeRole)) && person.revoke(r)
            }
          >
            <ShieldOff /> {s.removeRole}: {s.roleName(r)}
          </Button>
        ))}
      </div>
      <Private st={st} canRestrict={can("network.restrict")} onRestrict={(ip) => person.restrictAddress(ip, 30)} />
      <div>
        <h3 className="mb-2 text-sm font-medium">{s.history}</h3>
        <LogList user={userId} />
      </div>
    </div>
  );
}

/** The details only some staff see: how they sign in, where from, what they were called, who else. */
function Private({
  st,
  canRestrict,
  onRestrict,
}: {
  st: StaffUserState;
  canRestrict: boolean;
  onRestrict: (ip: string) => void;
}) {
  if (st.email == null && st.addresses == null && st.names == null && st.billing == null) return null;
  return (
    <div>
      <h3 className="mb-2 text-sm font-medium">{s.privateDetails}</h3>
      <dl className="grid gap-3 rounded-lg border p-4">
        {st.email != null && (
          <Row label={s.byEmail}>
            {st.email} {!st.emailVerified && <span className="text-subtle">({s.unverified})</span>}
          </Row>
        )}
        {st.signIn && <Row label={s.signsInWith}>{st.signIn.join(", ")}</Row>}
        {st.billing && <Row label={s.billingLabel}>{st.billing}</Row>}
        {st.names && (
          <Row label={s.pastNames}>
            <ul className="grid gap-0.5">
              {st.names.map((n, i) => (
                <li key={i} className={n.undone ? "line-through opacity-60" : undefined}>
                  {n.kind === "username" ? `@${n.value}` : (n.value ?? "—")}{" "}
                  <span className="text-subtle">
                    · {date(n.at)}
                    {n.byStaff && ` · ${s.staff}`}
                  </span>
                </li>
              ))}
            </ul>
          </Row>
        )}
        {st.addresses && (
          <Row label={s.addresses}>
            <ul className="grid gap-0.5">
              {st.addresses.map((a) => (
                <li key={a.ip} className="flex flex-wrap items-center gap-x-2">
                  <span className="font-mono">{a.ip}</span>
                  <span className="text-subtle">
                    {date(a.first)} – {date(a.last)} · {a.uses}×
                  </span>
                  {canRestrict && (
                    <button type="button" className="text-xs underline" onClick={() => onRestrict(a.ip)}>
                      {s.restrictAddress}
                    </button>
                  )}
                </li>
              ))}
            </ul>
          </Row>
        )}
        {st.related && (
          <Row label={s.related}>
            {st.related.length === 0 ? (
              <span className="text-subtle">{s.noRelated}</span>
            ) : (
              <ul className="grid gap-0.5">
                {st.related.map((r) => (
                  <li key={r.person.id}>
                    {r.person.username ? (
                      <Link to={`/u/${r.person.username}`} className="underline">
                        {r.person.label}
                      </Link>
                    ) : (
                      r.person.label
                    )}{" "}
                    <span className="text-subtle">· {r.why.map((w) => s.why(w)).join(", ")}</span>
                  </li>
                ))}
              </ul>
            )}
          </Row>
        )}
      </dl>
    </div>
  );
}

/** Staff tools on a public deck's page: taking it off Discover, and its history. */
export function DeckPanel({ deckId, preview }: { deckId: string; preview: PreviewScreen }) {
  const staff = useStaff("decks.remove");
  if (!staff.show) return null;
  return (
    <Section title={s.moderation}>
      {staff.locked ? (
        <PasskeyNeeded />
      ) : (
        <div className="grid gap-4">
          <div>
            <RemoveDeckDialog onSubmit={(reason, note, takeDown) => preview.removeFromDiscover(reason, note, takeDown)}>
              <Button variant="outline">{s.removeFromDiscover}</Button>
            </RemoveDeckDialog>
          </div>
          <LogList deck={deckId} />
        </div>
      )}
    </Section>
  );
}
