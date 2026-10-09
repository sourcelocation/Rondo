import { ChevronDown, Compass, Download, Layers, Play, Plus } from "lucide-react";
import { useEffect } from "react";
import { Link } from "react-router";
import { toast } from "sonner";
import { DeckTree, SmartDecks, useHome } from "@/components/decks";
import { Empty, Header, Loading, Ring, Section } from "@/components/kit";
import { Button } from "@/components/ui/button";
import { app, date, s, type HomeState } from "@/rondo";
import { useOverlay } from "@/routing";
import { cn } from "cn";

/** Today at a glance: what's due, a button to study it, and how the week went. */
function Today({ st }: { st: HomeState }) {
  const total = st.due + st.studied;
  return (
    <section className="mb-6 flex flex-col items-center gap-5 rounded-xl bg-surface p-6 text-center sm:flex-row sm:text-left">
      <Ring value={total ? Math.round((100 * st.studied) / total) : 100} size={88} />
      <div className="min-w-0 flex-1">
        <h2>{st.headline}</h2>
        <p className="mt-1 text-sm text-subtle">
          {st.summary}
          {st.streak > 1 && ` · ${s.streak(st.streak)}`}
        </p>
        {st.due > 0 && (
          <Button asChild className="mt-4">
            <Link to="/study">
              <Play /> {st.studied > 0 ? s.keepGoing : s.studyAll}
            </Link>
          </Button>
        )}
      </div>
    </section>
  );
}

/** The last seven days as dots, leading to Progress. */
function Week({ week }: { week: number[] }) {
  const answers = week.reduce((a, b) => a + b, 0);
  return (
    <Link
      to="/progress"
      className="mb-10 flex flex-wrap items-center justify-between gap-4 rounded-xl border p-4 hover:bg-accent/50"
    >
      <span>
        <span className="block text-sm font-medium">{s.thisWeek}</span>
        <span className="text-sm text-subtle">
          {s.answers(answers)} · {s.insights}
        </span>
      </span>
      <span className="flex gap-3">
        {week.map((n, i) => {
          const day = new Date(app.now() - (6 - i) * 86_400_000);
          return (
            <span
              key={i}
              className="flex flex-col items-center gap-1"
              title={`${date(day.getTime())}: ${s.answers(n)}`}
            >
              <span
                className={cn(
                  "size-3 rounded-full",
                  n > 0 ? "bg-primary" : "border bg-surface",
                  i === 6 && "ring-2 ring-primary ring-offset-2 ring-offset-background",
                )}
              />
              <span className="text-[11px] text-muted-foreground">
                {day.toLocaleDateString(undefined, { weekday: "narrow" })}
              </span>
            </span>
          );
        })}
      </span>
    </Link>
  );
}

export function Home() {
  const [st, home] = useHome();
  const created = useOverlay("new");
  const opened = st?.opened.join();
  useEffect(
    () =>
      opened
        ?.split(",")
        .filter(Boolean)
        .forEach((name) => toast(s.opened(name))),
    [opened],
  );
  if (!st) return <Loading />;
  const empty = st.mine.length === 0 && st.shared.length === 0;
  return (
    <>
      <Header title={s.home} />
      {!empty && (
        <>
          <Today st={st} />
          <Week week={st.week} />
        </>
      )}
      <Section
        title={s.yourDecks}
        action={
          !empty && (
            <Button variant="ghost" size="sm" onClick={() => created.open("deck")}>
              <Plus /> {s.newDeck}
            </Button>
          )
        }
      >
        {st.mine.length ? (
          <DeckTree items={st.mine} />
        ) : (
          <Empty
            icon={Layers}
            title={s.homeEmpty}
            action={<Button onClick={() => created.open("deck")}>{s.createFirstDeck}</Button>}
          >
            {s.homeEmptyHint}
          </Empty>
        )}
      </Section>
      {st.shared.length > 0 && (
        <Section title={s.sharedWithYou}>
          <DeckTree items={st.shared} />
        </Section>
      )}
      {st.smart.length > 0 && (
        <Section title={s.smartDecks}>
          <SmartDecks items={st.smart} />
        </Section>
      )}
      <div className="mb-10 flex flex-wrap gap-3">
        <Button variant="outline" asChild>
          <Link to="/import">
            <Download /> {s.importDecks}
          </Link>
        </Button>
        <Button variant="outline" asChild>
          <Link to="/discover">
            <Compass /> {s.discoverDecks}
          </Link>
        </Button>
      </div>
      {st.deleted.length > 0 && (
        <details className="group">
          <summary className="flex cursor-pointer list-none items-center justify-between rounded-lg p-2 text-sm text-muted-foreground hover:bg-accent/60">
            {s.recentlyDeleted} ({st.deleted.length})
            <ChevronDown className="size-4 transition-transform group-open:rotate-180" />
          </summary>
          <p className="mt-2 px-2 text-sm text-muted-foreground">{s.deletedNote}</p>
          {st.deleted.map((r) => (
            <div key={r.id} className="flex items-center gap-3 px-2 py-2">
              <span className="min-w-0 flex-1 truncate">{r.label}</span>
              <span className="text-xs text-muted-foreground">{date(r.deletedAt)}</span>
              <Button size="sm" variant="ghost" onClick={() => home.restore(r.id, r.deck)}>
                {s.restore}
              </Button>
            </div>
          ))}
        </details>
      )}
    </>
  );
}
