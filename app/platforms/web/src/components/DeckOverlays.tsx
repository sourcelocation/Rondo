import { Copy, Download, Lock, Share } from "lucide-react";
import { useEffect, useState } from "react";
import { Link } from "react-router";
import { toast } from "sonner";
import { ColorSwatches, EmojiPicker, LanguagePicker } from "@/components/decks";
import { confirm, Empty, Loading, Row, Rows, Spinner, WholeInput } from "@/components/kit";
import { Locked } from "@/components/Pro";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { Slider } from "@/components/ui/slider";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import {
  app,
  date,
  s,
  useScreen,
  type DeckSettingsScreen,
  type DeckSettingsState,
  type Download as File,
  type FileFormat,
} from "@/rondo";
import { useOverlay } from "@/routing";
import { cn } from "cn";

/**
 * A deck's settings, sharing and export, over whatever page opened them (`?settings=<deck>`,
 * `?share=<deck>`, `?export=<deck>`): its own page, or any menu of it. Mounted once.
 */
export function DeckOverlays() {
  const settings = useOverlay("settings");
  const share = useOverlay("share");
  const exported = useOverlay("export");
  return (
    <>
      {settings.value && <SettingsSheet deckId={settings.value} onClose={settings.close} />}
      {share.value && <ShareSheet deckId={share.value} onClose={share.close} />}
      {exported.value && <ExportSheet deckId={exported.value} onClose={exported.close} />}
    </>
  );
}

function SettingsSheet({ deckId, onClose }: { deckId: string; onClose: () => void }) {
  const [st, deck] = useScreen(() => app.deckSettings(deckId), [deckId]);
  // Gone, or no longer theirs to change: there's nothing to set.
  useEffect(() => void (st?.gone && onClose()), [st?.gone]); // eslint-disable-line react-hooks/exhaustive-deps
  if (!st || st.gone) return null;
  return <DeckSettings key={deckId} st={st} deck={deck} onClose={onClose} />;
}

const Field = ({ label, children, hint }: { label: string; children: React.ReactNode; hint?: string }) => (
  <div className="grid gap-2">
    <Label>{label}</Label>
    {children}
    {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
  </div>
);

/** Everything set once and rarely changed. Each change is saved as it's made. */
function DeckSettings({ st, deck, onClose }: { st: DeckSettingsState; deck: DeckSettingsScreen; onClose: () => void }) {
  const [name, setName] = useState(st.name);
  const [description, setDescription] = useState(st.description ?? "");
  const [retention, setRetention] = useState(st.retention);
  return (
    <Sheet open onOpenChange={(o) => !o && onClose()}>
      <SheetContent className="w-full gap-0 overflow-y-auto sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{s.deckSettings}</SheetTitle>
          <SheetDescription className="sr-only">{s.deckSettings}</SheetDescription>
        </SheetHeader>
        <div className="grid gap-6 px-4 pb-8">
          <div className="flex items-center gap-3">
            <EmojiPicker value={st.icon ?? null} onChange={(icon) => deck.setIcon(icon)} />
            <Input
              aria-label={s.deckName}
              value={name}
              maxLength={100}
              className="h-12"
              onChange={(e) => setName(e.target.value.replace(/[\t\n]/g, ""))}
              onBlur={() => (name.trim() && name !== st.name ? deck.setName(name) : setName(st.name))}
            />
          </div>
          <ColorSwatches value={st.color} onChange={(color) => deck.setColor(color)} />
          <Textarea
            aria-label={s.description}
            placeholder={s.description}
            value={description}
            maxLength={2000}
            onChange={(e) => setDescription(e.target.value)}
            onBlur={() => description !== (st.description ?? "") && deck.setDescription(description)}
          />
          <Field label={s.language}>
            <LanguagePicker
              value={st.language ?? null}
              onChange={(language) => deck.setLanguage(language)}
              any={s.none}
            />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label={s.newPerDay}>
              <WholeInput value={st.newPerDay} onCommit={(n) => deck.setNewPerDay(n)} />
            </Field>
            <Field label={s.reviewsPerDay}>
              <WholeInput value={st.reviewsPerDay} onCommit={(n) => deck.setReviewsPerDay(n)} />
            </Field>
          </div>
          <div className="grid gap-3">
            <div className="flex items-center justify-between">
              <Label>{s.retention}</Label>
              <span className="text-sm font-medium tabular-nums">{retention}%</span>
            </div>
            <Slider
              min={st.minRetention}
              max={st.maxRetention}
              value={[retention]}
              onValueChange={([r]) => setRetention(r ?? retention)}
              onValueCommit={([r]) => r != null && deck.setRetention(r)}
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

/** Hands [file] to the browser to save. */
const download = (file: File) => {
  const url = URL.createObjectURL(new Blob([file.bytes as unknown as BlobPart], { type: file.mime }));
  const a = Object.assign(document.createElement("a"), { href: url, download: file.name });
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
};

/** The deck as a file: a Rondo file, CSV, or an Anki package. */
function ExportSheet({ deckId, onClose }: { deckId: string; onClose: () => void }) {
  const [st, exported] = useScreen(() => app.export(deckId), [deckId]);
  const [progress, setProgress] = useState(true);
  const run = (format: FileFormat) => exported.run(format, progress, (file) => file && download(file));
  return (
    <Sheet open onOpenChange={(o) => !o && onClose()}>
      <SheetContent className="w-full overflow-y-auto sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{s.exportTitle}</SheetTitle>
          <SheetDescription className="sr-only">{s.exportTitle}</SheetDescription>
        </SheetHeader>
        {!st ? (
          <Loading />
        ) : (
          <div className="grid gap-6 px-4 pb-8">
            <Rows>
              {st.formats.map((f) => (
                <Row
                  key={f.name}
                  title={s.exportFormat(f)}
                  hint={s.exportFormatHint(f)}
                  action={
                    <Button variant="secondary" size="sm" disabled={st.busy != null} onClick={() => run(f)}>
                      {st.busy === f ? <Spinner /> : <Download />} {st.busy === f ? s.exporting : s.exportDeck}
                    </Button>
                  }
                />
              ))}
            </Rows>
            <Rows>
              <Row
                label
                title={s.exportProgress}
                hint={s.exportProgressHint}
                action={<Switch checked={progress} onCheckedChange={setProgress} />}
              />
            </Rows>
          </div>
        )}
      </SheetContent>
    </Sheet>
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
