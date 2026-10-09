import { Copy, Lock, LogOut, Minus, Play, Plus, Search, Settings2, Share, Share2, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { toast } from "sonner";
import { ColorSwatches, EmojiPicker, LanguagePicker } from "@/components/decks";
import { confirm, DeckLook, Empty, Header, Loading, Ring, Section } from "@/components/kit";
import { Locked } from "@/components/Pro";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { Slider } from "@/components/ui/slider";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { app, date, s, useScreen, type DeckItem, type DeckScreen, type DeckState } from "@/rondo";
import { useOverlay, useUp } from "@/routing";
import { cn } from "cn";

const Field = ({ label, children, hint }: { label: string; children: React.ReactNode; hint?: string }) => (
  <div className="grid gap-2">
    <Label>{label}</Label>
    {children}
    {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
  </div>
);

type Look = {
  name: string;
  icon: string | null;
  color: number;
  description: string;
  language: string | null;
  newPerDay: number;
  reviewsPerDay: number;
  retention: number;
  gated: boolean;
};

const lookOf = (st: DeckState): Look => ({
  name: st.deck.name,
  icon: st.deck.icon ?? null,
  color: st.deck.color,
  description: st.description ?? "",
  language: st.language ?? null,
  newPerDay: st.newPerDay,
  reviewsPerDay: st.reviewsPerDay,
  retention: st.retention,
  gated: st.gated,
});

/** Saves [change] on top of what the deck has now. */
const save = (deck: DeckScreen, st: DeckState, change: Partial<Look>) => {
  const v = { ...lookOf(st), ...change };
  deck.save(v.name, v.icon, v.color, v.description, v.language, v.newPerDay, v.reviewsPerDay, v.retention, v.gated);
};

/** A whole number from an input, kept within [min]…[max]; null while it's not a number. */
const whole = (text: string, min: number, max: number) => {
  const n = Number.parseInt(text, 10);
  return Number.isNaN(n) ? null : Math.min(max, Math.max(min, n));
};

/** Everything set once and rarely changed. Each change is saved as it's made. */
function DeckSettings({ st, deck, onClose }: { st: DeckState; deck: DeckScreen; onClose: () => void }) {
  const [name, setName] = useState(st.deck.name);
  const [description, setDescription] = useState(st.description ?? "");
  const [limits, setLimits] = useState({ n: String(st.newPerDay), r: String(st.reviewsPerDay) });
  const [retention, setRetention] = useState(st.retention);
  const set = (change: Partial<Look>) => save(deck, st, change);
  return (
    <Sheet open onOpenChange={(o) => !o && onClose()}>
      <SheetContent className="w-full gap-0 overflow-y-auto sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{s.deckSettings}</SheetTitle>
          <SheetDescription className="sr-only">{s.deckSettings}</SheetDescription>
        </SheetHeader>
        <div className="grid gap-6 px-4 pb-8">
          <div className="flex items-center gap-3">
            <EmojiPicker value={st.deck.icon ?? null} onChange={(icon) => set({ icon })} />
            <Input
              aria-label={s.deckName}
              value={name}
              maxLength={100}
              className="h-12"
              onChange={(e) => setName(e.target.value.replace(/[\t\n]/g, ""))}
              onBlur={() => (name.trim() && name !== st.deck.name ? set({ name }) : setName(st.deck.name))}
            />
          </div>
          <ColorSwatches value={st.deck.color} onChange={(color) => set({ color })} />
          <Textarea
            aria-label={s.description}
            placeholder={s.description}
            value={description}
            maxLength={2000}
            onChange={(e) => setDescription(e.target.value)}
            onBlur={() => description !== (st.description ?? "") && set({ description })}
          />
          <Field label={s.language}>
            <LanguagePicker value={st.language ?? null} onChange={(language) => set({ language })} any={s.none} />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label={s.newPerDay}>
              <Input
                inputMode="numeric"
                value={limits.n}
                onChange={(e) => setLimits({ ...limits, n: e.target.value })}
                onBlur={() => {
                  const n = whole(limits.n, 0, 9999) ?? st.newPerDay;
                  setLimits({ ...limits, n: String(n) });
                  if (n !== st.newPerDay) set({ newPerDay: n });
                }}
              />
            </Field>
            <Field label={s.reviewsPerDay}>
              <Input
                inputMode="numeric"
                value={limits.r}
                onChange={(e) => setLimits({ ...limits, r: e.target.value })}
                onBlur={() => {
                  const r = whole(limits.r, 0, 99999) ?? st.reviewsPerDay;
                  setLimits({ ...limits, r: String(r) });
                  if (r !== st.reviewsPerDay) set({ reviewsPerDay: r });
                }}
              />
            </Field>
          </div>
          <div className="grid gap-3">
            <div className="flex items-center justify-between">
              <Label>{s.retention}</Label>
              <span className="text-sm font-medium tabular-nums">{retention}%</span>
            </div>
            <Slider
              min={70}
              max={99}
              value={[retention]}
              onValueChange={([r]) => setRetention(r ?? retention)}
              onValueCommit={([r]) => r != null && set({ retention: r })}
            />
            <div className="flex justify-between text-xs text-muted-foreground">
              <span>{s.fewerReviews}</span>
              <span>{s.rememberMore}</span>
            </div>
          </div>
        </div>
      </SheetContent>
    </Sheet>
  );
}

/** A sub-deck: what's left to study or what it waits for, and, in levels, what opens it. */
function SubDeck({ st, deck, i }: { st: DeckState; deck: DeckScreen; i: number }) {
  const d = st.children[i]!;
  const at = st.unlockAt[i] ?? 100;
  const levels = st.gated && i > 0;
  return (
    <div className="flex items-center gap-3 px-4 py-3">
      <Ring value={d.ring} locked={d.locked} size={32} />
      <DeckLook icon={d.icon} color={d.color} className="text-lg" />
      <Link to={`/decks/${d.id}`} className="min-w-0 flex-1">
        <div className="truncate">{d.name}</div>
        <div className="truncate text-sm text-muted-foreground">
          {levels ? `${s.unlockAt} ${s.unlockAfter(at, st.children[i - 1]!.name)} · ` : ""}
          {d.detail}
        </div>
      </Link>
      {levels && st.canEdit && (
        <div className="hidden items-center sm:flex">
          <Button
            size="icon-xs"
            variant="ghost"
            aria-label={s.lower}
            disabled={at <= 50}
            onClick={() => deck.setUnlockAt(d.id, at - 10)}
          >
            <Minus />
          </Button>
          <span className="w-10 text-center text-sm tabular-nums">{at}%</span>
          <Button
            size="icon-xs"
            variant="ghost"
            aria-label={s.raise}
            disabled={at >= 100}
            onClick={() => deck.setUnlockAt(d.id, at + 10)}
          >
            <Plus />
          </Button>
        </div>
      )}
      {d.locked && (
        <Button size="sm" variant="ghost" onClick={() => deck.unlock(d.id)}>
          <Lock /> {s.unlockNow}
        </Button>
      )}
    </div>
  );
}

/** The deck's first notes, each opening the editor. */
function Notes({ id }: { id: string }) {
  const [st] = useScreen(() => app.browse(id, null), [id]);
  const note = useOverlay("note");
  if (!st || st.notes.length === 0) return null;
  const shown = st.notes.slice(0, 20);
  return (
    <Section
      title={s.notesCount}
      action={
        <Button variant="ghost" size="sm" asChild>
          <Link to={`/browse?deck=${id}`}>
            <Search /> {s.browse}
          </Link>
        </Button>
      }
    >
      <div className="divide-y overflow-hidden rounded-lg bg-card shadow-sm">
        {shown.map((n) => (
          <button
            key={n.id}
            type="button"
            className="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-accent/50"
            onClick={() => note.open(n.id)}
          >
            <span className="min-w-0 flex-1 truncate">{n.text}</span>
            <span className="text-xs text-muted-foreground">{n.template}</span>
          </button>
        ))}
      </div>
      {st.notes.length > shown.length && (
        <Button variant="ghost" className="mt-2" asChild>
          <Link to={`/browse?deck=${id}`}>{s.showMore}</Link>
        </Button>
      )}
    </Section>
  );
}

export function Deck() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const goUp = useUp();
  const [st, deck] = useScreen(() => app.deck(id), [id]);
  const sheet = useOverlay("sheet");
  const note = useOverlay("note");
  const created = useOverlay("new");
  if (!st) return <Loading />;
  if (st.gone) return <Empty title={s.notFound}>{s.error("not_found")}</Empty>;
  const d: DeckItem = st.deck;
  const up = d.parentId && st.path.length > 1 ? `/decks/${d.parentId}` : "/";
  const due = d.newCount + d.learning + d.review;
  const leave = async () => {
    if (await confirm(`${s.leave} “${d.name}”?`, s.leaveHint, s.leave))
      deck.leave(() => navigate("/", { replace: true }));
  };
  return (
    <>
      <Header
        up={up}
        title={
          <span className="flex items-center gap-3">
            {d.icon && <span>{d.icon}</span>}
            <span className="truncate">{d.name}</span>
          </span>
        }
        sub={st.path.length > 1 ? st.path.slice(0, -1).join(" › ") : d.level}
      >
        <Button variant="ghost" size="icon" aria-label={s.browseDeck} asChild>
          <Link to={`/browse?deck=${id}`}>
            <Search />
          </Link>
        </Button>
        {st.canEdit && (
          <Button variant="ghost" size="icon" aria-label={s.deckSettings} onClick={() => sheet.open("settings")}>
            <Settings2 />
          </Button>
        )}
        {st.owned && (
          <Button variant="ghost" size="icon" aria-label={s.share} onClick={() => sheet.open("share")}>
            <Share2 />
          </Button>
        )}
      </Header>
      {st.frozen && <p className="mb-4 rounded-lg bg-warning px-4 py-3 text-sm">{s.removedByAuthor}</p>}
      {st.description && <p className="-mt-4 mb-6 whitespace-pre-line text-subtle">{st.description}</p>}
      {!st.canEdit && !st.frozen && <p className="-mt-2 mb-6 text-sm text-muted-foreground">{s.readOnlyDeck}</p>}
      <div className="mb-10 flex flex-col gap-2 sm:flex-row">
        {st.notes > 0 &&
          (due > 0 && !d.locked ? (
            <Button size="lg" className="sm:flex-1" asChild>
              <Link to={`/decks/${id}/study`}>
                <Play /> {s.studyN(due)}
              </Link>
            </Button>
          ) : (
            <p className="flex h-10 items-center text-sm text-subtle sm:flex-1">{s.nothingToStudy}</p>
          ))}
        {st.notes > 0 && (
          <Button size="lg" variant="outline" className="sm:flex-1" asChild>
            <Link to={`/browse?deck=${id}`}>
              <Search /> {s.viewNotes}
            </Link>
          </Button>
        )}
        {st.canEdit && (
          <Button
            size="lg"
            variant={st.notes > 0 ? "outline" : "default"}
            className="sm:flex-1"
            onClick={() => note.open("new", { in: id })}
          >
            <Plus /> {st.notes > 0 ? s.addNotes : s.createFirstNote}
          </Button>
        )}
      </div>
      {(st.children.length > 0 || st.canEdit) && (
        <Section
          title={st.gated ? s.levels : s.subdecks}
          action={
            st.canEdit && (
              <Button variant="ghost" size="sm" onClick={() => created.open("deck", { parent: id })}>
                <Plus /> {s.newShort}
              </Button>
            )
          }
        >
          {st.children.length > 0 && (
            <div className="divide-y overflow-hidden rounded-lg bg-card shadow-sm">
              {st.children.map((c, i) => (
                <SubDeck key={c.id} st={st} deck={deck} i={i} />
              ))}
              {st.canEdit && (st.children.length > 1 || st.gated) && (
                <label className="flex items-center justify-between gap-4 px-4 py-3">
                  <span>
                    <span className="block">{s.learnInOrder}</span>
                    <span className="text-sm text-muted-foreground">{s.levelsHint}</span>
                  </span>
                  <Switch checked={st.gated} onCheckedChange={(gated) => save(deck, st, { gated })} />
                </label>
              )}
            </div>
          )}
        </Section>
      )}
      <Notes id={id} />
      <div className="flex flex-wrap gap-2">
        {!st.owned && (
          <Button
            variant="secondary"
            onClick={() => deck.copy((copy) => (toast(s.copied), navigate(`/decks/${copy}`)))}
          >
            <Copy /> {s.copy}
          </Button>
        )}
        {st.anki && (
          <Button
            variant="secondary"
            title={s.convertHint}
            onClick={() => deck.convert((copy) => navigate(`/decks/${copy}`))}
          >
            {s.convert}
          </Button>
        )}
        {st.lent && (
          <Button variant="ghost" className="text-destructive" onClick={leave}>
            <LogOut /> {s.leave}
          </Button>
        )}
        {st.owned && (
          <Button
            variant="ghost"
            className="text-destructive"
            onClick={async () => {
              if (await confirm(`${s.delete} “${d.name}”?`, s.deleteDeckHint)) {
                deck.delete();
                goUp(up);
              }
            }}
          >
            <Trash2 /> {s.delete}
          </Button>
        )}
      </div>
      {sheet.value === "settings" && st.canEdit && <DeckSettings st={st} deck={deck} onClose={sheet.close} />}
      {sheet.value === "share" && st.owned && <ShareSheet deckId={id} onClose={sheet.close} />}
    </>
  );
}

function ShareSheet({ deckId, onClose }: { deckId: string; onClose: () => void }) {
  const [st, share] = useScreen(() => app.share(deckId), [deckId]);
  const [email, setEmail] = useState("");
  const [role, setRole] = useState(1);
  useEffect(() => setRole(1), [deckId]);
  const pro = useOverlay("pro");
  // Editors need the owner's Pro: without it, Editor is shown locked, and choosing it offers Pro.
  const locked = !st?.pro;
  const roles = (
    <SelectContent>
      <SelectItem value="1">{s.viewer}</SelectItem>
      <Locked locked={locked} reason={s.editorsNeedPro}>
        <SelectItem value="2">
          {s.editor} {locked && <Lock />}
        </SelectItem>
      </Locked>
    </SelectContent>
  );
  const choose = (r: string, set: (role: number) => void) => (r === "2" && locked ? pro.open("editors") : set(+r));
  const copyLink = async (link: string) => {
    await navigator.clipboard.writeText(link);
    toast(s.copied);
  };
  return (
    <Sheet open onOpenChange={(o) => !o && onClose()}>
      <SheetContent className="w-full overflow-y-auto sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{s.share}</SheetTitle>
          <SheetDescription className="sr-only">{s.share}</SheetDescription>
        </SheetHeader>
        <div className="grid gap-8 px-4 pb-8">
          {!st ? (
            <Loading />
          ) : st.offline ? (
            <Empty>{s.shareOffline}</Empty>
          ) : (
            <>
              <form
                className="grid gap-2"
                onSubmit={(e) => (e.preventDefault(), email && (share.invite(email, role), setEmail("")))}
              >
                <Label>{s.inviteByEmail}</Label>
                <div className="flex gap-2">
                  <Input
                    type="email"
                    required
                    placeholder={s.email}
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                  />
                  <Select value={String(role)} onValueChange={(r) => choose(r, setRole)}>
                    <SelectTrigger className="w-36">
                      <SelectValue />
                    </SelectTrigger>
                    {roles}
                  </Select>
                </div>
                <Button type="submit" disabled={!email}>
                  {s.invite}
                </Button>
              </form>
              <div className="grid gap-2">
                <Label>{s.link}</Label>
                {st.link ? (
                  <div className="flex gap-2">
                    <Input readOnly value={st.link} onFocus={(e) => e.target.select()} />
                    <Button variant="secondary" size="icon" aria-label={s.copyLink} onClick={() => copyLink(st.link!)}>
                      <Copy />
                    </Button>
                    {"share" in navigator && (
                      <Button
                        variant="secondary"
                        size="icon"
                        aria-label={s.send}
                        onClick={() => navigator.share({ url: st.link! }).catch(() => {})}
                      >
                        <Share />
                      </Button>
                    )}
                  </div>
                ) : (
                  <Button variant="secondary" onClick={() => share.createLink(role)}>
                    {s.createLink}
                  </Button>
                )}
                <p className="text-xs text-muted-foreground">{s.linkHint}</p>
              </div>
              {(st.members.length > 0 || st.invites.length > 0) && (
                <div className="grid gap-2">
                  <Label>{s.members}</Label>
                  {st.members.map((m) => (
                    <div key={m.userId} className="flex items-center gap-2">
                      <span className={cn("min-w-0 flex-1 truncate", !m.name && "text-muted-foreground")}>
                        {m.name ?? s.someone}
                      </span>
                      <Select
                        value={String(m.role)}
                        onValueChange={(r) => choose(r, (to) => share.setRole(m.userId, to))}
                      >
                        <SelectTrigger className="w-32">
                          <SelectValue />
                        </SelectTrigger>
                        {roles}
                      </Select>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={async () =>
                          (await confirm(s.removeTitle(m.name ?? null), s.removeHint, s.remove)) &&
                          share.remove(m.userId)
                        }
                      >
                        {s.remove}
                      </Button>
                    </div>
                  ))}
                  {st.invites.map((i) => (
                    <div key={i.id} className="flex items-center gap-2 text-muted-foreground">
                      <span className="min-w-0 flex-1 truncate">
                        {i.email ?? s.link} · {s.pending}
                      </span>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={async () =>
                          (await confirm(s.revokeTitle, s.revokeHint, s.revoke)) && share.revoke(i.id)
                        }
                      >
                        {s.revoke}
                      </Button>
                    </div>
                  ))}
                </div>
              )}
              {(st.restrictedUntil || st.removed) && (
                <p className="rounded-lg bg-warning px-4 py-3 text-sm">
                  {st.restrictedUntil
                    ? s.youreRestricted(
                        new Date(st.restrictedUntil).getFullYear() < 9000 ? date(st.restrictedUntil) : null,
                      )
                    : s.removedFromDiscover}{" "}
                  <a className="underline" href="/docs/rules" target="_blank" rel="noopener noreferrer">
                    {s.rules}
                  </a>
                </p>
              )}
              <label className="flex items-center justify-between gap-4">
                <span>
                  <span className="block font-medium">{st.published ? s.published : s.publish}</span>
                  {st.published && (
                    <span className="text-xs text-muted-foreground">
                      {s.followers(st.followers)}
                      {st.slug && (
                        <>
                          {" · "}
                          <Link className="text-link" to={`/d/${st.slug}`}>
                            {s.discover}
                          </Link>
                        </>
                      )}
                    </span>
                  )}
                </span>
                <Switch
                  checked={st.published}
                  disabled={st.removed || !!st.restrictedUntil}
                  onCheckedChange={(on) => (on ? share.publish() : share.unpublish())}
                />
              </label>
            </>
          )}
        </div>
      </SheetContent>
    </Sheet>
  );
}
