import { Gift } from "lucide-react";
import { useState } from "react";
import { Link, useParams } from "react-router";
import { Header, Loading } from "@/components/kit";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { app, date, s, useScreen } from "@/rondo";

/** Redeeming a promo code: typed, or from a link (/redeem/CODE). Web only: stores don't allow it in apps. */
export function Redeem() {
  const { code } = useParams();
  const [st, redeem] = useScreen(() => app.redeem(code ?? null), [code]);
  const [typed, setTyped] = useState(code ?? "");
  if (!st) return <Loading />;
  return (
    <>
      <Header up="/profile" title={s.redeemTitle} sub={s.redeemHint} />
      {st.until ? (
        <p className="flex items-center gap-3 rounded-lg border p-4">
          <Gift className="size-5 shrink-0" /> {s.proFrom(date(st.until))}
        </p>
      ) : !st.signedIn ? (
        <Button asChild>
          <Link to={`/sign-in?next=${encodeURIComponent(location.pathname.replace(/^\/app/, ""))}`}>{s.signIn}</Link>
        </Button>
      ) : (
        <form
          className="flex max-w-md gap-2"
          onSubmit={(e) => (e.preventDefault(), typed.trim() && redeem.redeem(typed))}
        >
          <Input
            aria-label={s.codeLabel}
            placeholder="XXXX-XXXX-XXXX"
            autoComplete="off"
            className="font-mono uppercase"
            value={typed}
            onChange={(e) => setTyped(e.target.value)}
          />
          <Button type="submit" disabled={st.busy || !typed.trim()}>
            {s.redeemAction}
          </Button>
        </form>
      )}
    </>
  );
}
