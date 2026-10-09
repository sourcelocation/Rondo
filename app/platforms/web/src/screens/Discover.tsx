import { Compass, Flag as Flag2, Search, Users } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Link, useLocation, useNavigate, useParams } from "react-router";
import { CardSurface, Side } from "@/components/Card";
import { Flag } from "@/components/Flag";
import { ReportDialog } from "@/components/ReportDialog";
import { DeckPanel } from "@/components/StaffPanel";
import { LanguagePicker } from "@/components/decks";
import { deckColor, Empty, Header, Loading, Section, Spinner } from "@/components/kit";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { app, languageName, s, useScreen, type PublicItem } from "@/rondo";
import { useOpener } from "@/routing";

/** Who made a deck, its language, size and followers; [linked]: the author leads to their profile. */
const Meta = ({ item, linked }: { item: PublicItem; linked?: boolean }) => (
  <span className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
    {item.owner && (
      <span className="flex items-center gap-1.5">
        {linked && item.ownerUsername ? (
          <Link to={`/u/${item.ownerUsername}`} className="hover:text-foreground hover:underline">
            {s.by(item.owner)}
          </Link>
        ) : (
          s.by(item.owner)
        )}
        <Flag code={item.ownerFlag} />
      </span>
    )}
    {item.language && <span>{languageName(item.language)}</span>}
    <span>{s.notes(item.notes)}</span>
    <span className="flex items-center gap-1">
      <Users className="size-3.5" /> {item.followers}
    </span>
    {item.following && <Badge variant="secondary">{s.following}</Badge>}
  </span>
);

const Tile = ({ item }: { item: PublicItem }) => (
  <span
    className="grid size-12 shrink-0 place-items-center rounded-xl bg-muted text-2xl"
    style={
      deckColor(item.color)
        ? { background: `color-mix(in srgb, ${deckColor(item.color)} 18%, transparent)` }
        : undefined
    }
  >
    {item.icon ?? item.name[0]}
  </span>
);

/** A public deck in a list, leading to its page. */
export const DeckRow = ({ item }: { item: PublicItem }) => (
  <Link to={`/d/${item.slug}`} className="flex gap-4 rounded-lg p-3 hover:bg-accent/50">
    <Tile item={item} />
    <span className="min-w-0 flex-1">
      <span className="block truncate font-medium">{item.name}</span>
      {item.description && <span className="line-clamp-2 text-sm text-subtle">{item.description}</span>}
      <Meta item={item} />
    </span>
  </Link>
);

export function Discover() {
  const [st, discover] = useScreen(() => app.discover());
  const [query, setQuery] = useState("");
  const timer = useRef<number>(undefined);
  useEffect(() => {
    clearTimeout(timer.current);
    timer.current = window.setTimeout(
      () => st && query !== st.query && discover.search(query, st.language ?? null),
      300,
    );
  });
  if (!st) return <Loading />;
  return (
    <>
      <Header title={s.discover} sub={s.discoverHint} />
      <div className="mb-6 flex flex-col gap-2 sm:flex-row">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            type="search"
            aria-label={s.search}
            placeholder={s.search}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            className="pl-9"
          />
        </div>
        <LanguagePicker
          value={st.language ?? null}
          onChange={(l) => discover.search(st.query, l)}
          any={s.anyLanguage}
        />
      </div>
      <ToggleGroup
        type="single"
        variant="outline"
        className="mb-4 justify-start"
        value={st.sort}
        onValueChange={(v) => v && discover.sortBy(v)}
      >
        {(
          [
            ["top", s.sortTop],
            ["new", s.sortNew],
            ["hot", s.sortHot],
          ] as const
        ).map(([v, label]) => (
          <ToggleGroupItem key={v} value={v} className="px-3">
            {label}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
      {st.offline ? (
        <Empty icon={Compass}>{s.discoverOffline}</Empty>
      ) : (
        st.items.length === 0 && (
          <Empty icon={st.query ? Search : Compass}>{st.query ? s.noMatches : s.nothingShared}</Empty>
        )
      )}
      <div className="grid gap-1">
        {st.items.map((i) => (
          <DeckRow key={i.slug ?? i.deckId} item={i} />
        ))}
      </div>
      {st.more && (
        <Button variant="ghost" className="mt-4 w-full" onClick={() => discover.more()}>
          {s.showMore}
        </Button>
      )}
    </>
  );
}

/** A choice with a line saying what it means. */
const Choice = ({ children, hint }: { children: React.ReactNode; hint: string }) => (
  <div className="grid gap-2">
    {children}
    <p className="text-sm text-muted-foreground">{hint}</p>
  </div>
);

/**
 * A deck before it's yours, the same however it was found: on Discover or through a share link
 * (`/d/:slug`), or an invitation (`/i/:token`). Shown from the server, so nothing needs to sync first.
 */
export function Preview() {
  const { slug, token } = useParams();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const [st, preview] = useScreen(() => app.preview(slug ?? null, token ?? null), [slug, token]);
  useEffect(() => {
    if (st?.opening) navigate(`/decks/${st.opening}`, { replace: true });
  }, [st?.opening, navigate]);
  const up = useOpener(token ? "/" : "/discover");
  if (!st) return <Loading />;
  if (!st.item)
    return (
      <>
        <Header up={up} title={s.notFound} />
        <Empty>{token ? s.invitationGone : s.error("not_found")}</Empty>
      </>
    );
  const i = st.item;
  const signIn = () => navigate(`/sign-in?next=${encodeURIComponent(pathname)}`);
  return (
    <>
      <Header up={up} title={`${i.icon ? `${i.icon} ` : ""}${i.name}`} sub={<Meta item={i} linked />}>
        {st.signedIn && !token && (
          <ReportDialog kind="deck" id={i.deckId}>
            <Button variant="ghost" size="sm">
              <Flag2 /> {s.report}
            </Button>
          </ReportDialog>
        )}
      </Header>
      {token && <p className="-mt-4 mb-6 text-lg text-subtle">{s.invited(i.owner ?? null, st.role === 2)}</p>}
      {i.description && <p className="mb-8 whitespace-pre-line">{i.description}</p>}
      <div className="mb-12 grid gap-4 sm:grid-cols-2">
        {st.busy ? (
          <p className="flex items-center gap-2 text-subtle">
            <Spinner /> {s.adding}
          </p>
        ) : i.following ? (
          <Button size="lg" asChild>
            <Link to={`/decks/${i.deckId}`}>{s.open}</Link>
          </Button>
        ) : token ? (
          <div className="flex flex-wrap gap-2">
            <Button size="lg" onClick={st.signedIn ? () => preview.accept() : signIn}>
              {st.signedIn ? s.accept : s.signIn}
            </Button>
            <Button size="lg" variant="ghost" asChild>
              <Link to="/">{s.notNow}</Link>
            </Button>
          </div>
        ) : (
          <>
            <Choice hint={s.followHint}>
              <Button size="lg" onClick={st.signedIn ? () => preview.follow() : signIn}>
                {s.follow}
              </Button>
            </Choice>
            <Choice hint={s.copyHint}>
              <Button size="lg" variant="outline" onClick={st.signedIn ? () => preview.copy() : signIn}>
                {s.copy}
              </Button>
            </Choice>
          </>
        )}
      </div>
      {st.decks.length > 1 && (
        <Section title={s.decks}>
          <ul className="grid gap-1 text-sm">
            {st.decks.map((d) => (
              <li key={d}>{d}</li>
            ))}
          </ul>
        </Section>
      )}
      {!token && <DeckPanel deckId={i.deckId} preview={preview} />}
      {st.samples.length > 0 && (
        <Section title={s.samples}>
          <div className="grid gap-4">
            {st.samples.map((c, n) => (
              <CardSurface key={n} small className="grid gap-4 sm:grid-cols-2">
                <Side side={c.front} language={i.language} />
                <Side side={c.back} language={i.language} />
              </CardSurface>
            ))}
          </div>
        </Section>
      )}
    </>
  );
}
