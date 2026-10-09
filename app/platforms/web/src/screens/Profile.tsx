import { Compass, Flag as Flag2, UserX } from "lucide-react";
import { ActivityCalendar } from "react-activity-calendar";
import { useParams } from "react-router";
import { Flag } from "@/components/Flag";
import { ReportDialog } from "@/components/ReportDialog";
import { PersonPanel } from "@/components/StaffPanel";
import { Empty, Header, Loading, Section } from "@/components/kit";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { app, date, s, useApp, useScreen, useTheme, type ActivityItem } from "@/rondo";
import { useOpener } from "@/routing";
import { DeckRow } from "@/screens/Discover";

const Stat = ({ label, value }: { label: string; value: string | number }) => (
  <div className="rounded-lg border p-4">
    <div className="text-2xl font-medium tabular-nums">{value}</div>
    <div className="text-sm text-subtle">{label}</div>
  </div>
);

/** A year of reviews as a heatmap, with the totals around it. */
function Activity({ a, onYear }: { a: ActivityItem; onYear: (year: number) => void }) {
  const theme = useTheme();
  const scheme = theme === "system" ? undefined : theme;
  // The calendar spans the whole year: empty days at its ends set the range.
  const days = [
    { date: `${a.year}-01-01`, count: 0, level: 0 },
    ...a.days.map((d) => ({ date: d.date, count: d.reviews, level: d.level })),
    { date: `${a.year}-12-31`, count: 0, level: 0 },
  ].filter((d, i, all) => i === 0 || d.date !== all[i - 1]!.date);
  return (
    <Section
      title={s.activity}
      action={
        a.years.length > 1 &&
        a.years.map((y) => (
          <Button key={y} size="sm" variant={y === a.year ? "secondary" : "ghost"} onClick={() => onYear(y)}>
            {y}
          </Button>
        ))
      }
    >
      <div className="mb-4 grid grid-cols-2 gap-2 sm:grid-cols-4">
        <Stat label={s.reviewsAllTime} value={a.reviews.toLocaleString()} />
        {a.learned != null ? (
          <Stat label={s.cardsLearned} value={a.learned.toLocaleString()} />
        ) : (
          <Stat label={s.daysStudied} value={a.yearDays} />
        )}
        <Stat label={s.currentStreak} value={s.days(a.streak)} />
        <Stat label={s.longestStreak} value={s.days(a.longestStreak)} />
      </div>
      <div className="overflow-x-auto rounded-lg border p-4">
        <ActivityCalendar
          data={days}
          colorScheme={scheme}
          theme={{ light: ["#ebe8df", "#2f3bff"], dark: ["#2a2a28", "#8a92ff"] }}
          labels={{ totalCount: s.yearReviews(a.yearReviews, a.year) }}
          blockSize={11}
          blockMargin={3}
          fontSize={12}
        />
      </div>
      <p className="mt-2 text-sm text-subtle">
        {s.daysStudied}: {a.yearDays} · {s.studyTime}: {s.hours(a.yearMinutes)}
      </p>
    </Section>
  );
}

/** Someone's public page: who they are, their decks on Discover, and their activity. */
export function Profile() {
  const { username } = useParams();
  const signedIn = useApp()?.signedIn ?? false;
  const [st, profile] = useScreen(() => app.profile(username!, null), [username]);
  const up = useOpener("/discover");
  if (!st) return <Loading />;
  if (st.missing)
    return (
      <Empty icon={UserX} title={s.noProfile}>
        {s.noProfileHint}
      </Empty>
    );
  return (
    <>
      <Header
        up={up}
        title={
          <span className="flex items-center gap-2">
            {st.name ?? `@${st.username}`} <Flag code={st.flag} />
          </span>
        }
        sub={
          <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
            {st.name && <span>@{st.username}</span>}
            <span>{s.memberSince(date(st.joined))}</span>
            {st.pro && <Badge variant="secondary">{s.pro}</Badge>}
          </span>
        }
      >
        {st.mine && (
          <Button variant="outline" onClick={() => app.editProfile()}>
            {s.editProfile}
          </Button>
        )}
        {!st.mine && signedIn && (
          <ReportDialog kind="person" id={st.id}>
            <Button variant="ghost">
              <Flag2 /> {s.report}
            </Button>
          </ReportDialog>
        )}
      </Header>
      {st.movedFrom && <p className="mb-6 text-sm text-subtle">{s.movedFrom(st.movedFrom)}</p>}
      {!st.mine && <PersonPanel userId={st.id} />}
      {st.activity && <Activity a={st.activity} onYear={(y) => profile.showYear(y)} />}
      <Section title={s.publishedDecks}>
        {st.decks.length === 0 ? (
          <Empty icon={Compass}>{s.noPublished}</Empty>
        ) : (
          <div className="grid gap-1">
            {st.decks.map((i) => (
              <DeckRow key={i.deckId} item={i} />
            ))}
          </div>
        )}
      </Section>
    </>
  );
}
