import { Bar, BarChart, ResponsiveContainer, Tooltip, XAxis } from "recharts";
import { Header, Loading, Section } from "@/components/kit";
import { app, date, s, useScreen } from "@/rondo";

const Tile = ({ value, label, hint }: { value: string | number; label: string; hint?: string }) => (
  <div className="rounded-lg bg-surface p-4">
    <div className="rd-text-numeric-lg">{typeof value === "number" ? value.toLocaleString() : value}</div>
    <div className="text-xs text-muted-foreground">{label}</div>
    {hint && <div className="mt-1 text-xs text-muted-foreground">{hint}</div>}
  </div>
);

const SHADES = [25, 45, 70, 100];

/** Four shades by quartile of the days studied, so one long day doesn't wash out the rest. */
function levels(days: number[]): (n: number) => number {
  const studied = days.filter((n) => n > 0).sort((a, b) => a - b);
  const bounds = [1, 2, 3].map((q) => studied[Math.min(Math.floor((studied.length * q) / 4), studied.length - 1)] ?? 0);
  return (n) => (n <= 0 ? 0 : studied.length === 0 ? 1 : 1 + bounds.filter((b) => n > b).length);
}

const shade = (level: number) =>
  level === 0
    ? "var(--rd-color-bg-sunken)"
    : `color-mix(in srgb, var(--rd-color-state-review) ${SHADES[level - 1]}%, transparent)`;

/** A year of reviews, a week per column (Monday on top), opened at today. */
function Heatmap({ days }: { days: number[] }) {
  const level = levels(days);
  // 364 days back is the same weekday as today, so the year starts on today's row.
  const lead = (new Date().getDay() + 6) % 7;
  const studied = days.filter((n) => n > 0).length;
  return (
    <div>
      <div className="overflow-x-auto pb-2" dir="rtl" role="img" aria-label={`${s.reviewsYear}: ${studied}`}>
        <div dir="ltr" className="grid w-max grid-flow-col grid-rows-7 gap-[3px]">
          {[...Array(lead).fill(-1), ...days].map((n, i) => (
            <div
              key={i}
              className="size-3 rounded-[3px]"
              style={{ visibility: n < 0 ? "hidden" : undefined, background: shade(level(n)) }}
              title={
                n >= 0 ? `${date(app.now() - (days.length - 1 - (i - lead)) * 86_400_000)}: ${s.reviews(n)}` : undefined
              }
            />
          ))}
        </div>
      </div>
      <div className="mt-2 flex items-center gap-1 text-xs text-muted-foreground">
        {s.less}
        {[0, 1, 2, 3, 4].map((l) => (
          <span key={l} className="size-2.5 rounded-[2px]" style={{ background: shade(l) }} />
        ))}
        {s.moreShades}
      </div>
    </div>
  );
}

export function Insights() {
  const [st] = useScreen(() => app.insights());
  if (!st) return <Loading />;
  const forecast = st.forecast.map((n, i) => ({
    day: new Date(app.now() + i * 86_400_000).toLocaleDateString(undefined, { day: "numeric", month: "short" }),
    n,
  }));
  return (
    <>
      <Header title={s.insights} sub={st.streak ? s.streak(st.streak) : undefined} />
      <Section title={s.reviewsYear}>
        <Heatmap days={st.days} />
      </Section>
      <div className="mb-8 grid grid-cols-2 gap-3 sm:grid-cols-3">
        <Tile
          value={st.retention != null ? `${st.retention}%` : "—"}
          label={s.trueRetention}
          hint={st.retention != null ? s.retentionInfo : s.retentionPending}
        />
        <Tile value={st.cards} label={s.totalCards} />
        <Tile value={st.learned} label={s.learned} />
        <Tile value={st.suspended} label={s.suspendedCards} />
        <Tile value={st.minutes} label={s.minutes} />
      </div>
      <Section title={s.forecast}>
        <ResponsiveContainer width="100%" height={180}>
          <BarChart data={forecast}>
            <XAxis dataKey="day" tickLine={false} axisLine={false} fontSize={11} interval={6} />
            <Tooltip
              cursor={{ fill: "var(--rd-color-bg-sunken)" }}
              contentStyle={{ background: "var(--rd-color-bg-raised)", border: "none", borderRadius: 8 }}
              formatter={(n) => [n, s.reviews(Number(n))]}
            />
            <Bar dataKey="n" radius={[3, 3, 0, 0]} fill="var(--rd-color-state-review)" />
          </BarChart>
        </ResponsiveContainer>
      </Section>
    </>
  );
}
