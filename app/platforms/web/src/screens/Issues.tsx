import { Empty, Header, Loading } from "@/components/kit";
import { Button } from "@/components/ui/button";
import { app, s, useScreen } from "@/rondo";

/** Edits the server refused, each with what can be done about it. */
export function Issues() {
  const [st, issues] = useScreen(() => app.issues());
  if (!st) return <Loading />;
  return (
    <>
      <Header up="/settings/account" title={s.syncIssues} sub={s.issuesHint} />
      {st.items.length === 0 && <Empty>{s.noIssues}</Empty>}
      {st.items.map((i) => (
        <div key={`${i.entity}:${i.id}`} className="flex flex-wrap items-center gap-3 border-b py-3">
          <div className="min-w-0 flex-1">
            <div className="truncate font-medium">{i.label}</div>
            <div className="text-sm text-muted-foreground">{i.reason}</div>
          </div>
          {i.canCopy && (
            <Button size="sm" variant="secondary" onClick={() => issues.keepCopy(i.id, i.entity)}>
              {s.keepCopy}
            </Button>
          )}
          <Button size="sm" variant="ghost" onClick={() => issues.discard(i.id, i.entity)}>
            {s.discard}
          </Button>
        </div>
      ))}
    </>
  );
}
