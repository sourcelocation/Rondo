import { Check, Sparkles } from "lucide-react";
import { useEffect, useState, type ReactElement } from "react";
import { Link, useLocation } from "react-router";
import { Spinner } from "@/components/kit";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { app, s, useApp, useScreen } from "@/rondo";
import { useOverlay } from "@/routing";
import { cn } from "cn";

const go = (url: string) => (location.href = url);

/** What Pro adds, ticked: in Settings › Plan and the Pro dialog. */
export const ProFeatures = ({ className }: { className?: string }) => (
  <ul className={cn("grid gap-2 text-sm", className)}>
    {s.proFeatures.map((f) => (
      <li key={f} className="flex gap-2">
        <Check className="mt-0.5 size-4 shrink-0 text-primary" /> {f}
      </li>
    ))}
  </ul>
);

/** Paying for Pro: monthly or yearly, and the button that goes to pay. [busy]: a checkout is starting. */
export function Checkout({
  busy,
  disabled,
  onCheckout,
  className,
}: {
  busy: boolean;
  disabled?: boolean;
  onCheckout: (period: string) => void;
  className?: string;
}) {
  const [period, setPeriod] = useState("monthly");
  return (
    <div className={cn("flex flex-wrap items-center justify-between gap-3", className)}>
      <ToggleGroup
        type="single"
        variant="outline"
        aria-label={s.payEvery}
        value={period}
        onValueChange={(v) => v && setPeriod(v)}
      >
        <ToggleGroupItem value="monthly" className="px-3">
          {s.monthly}
        </ToggleGroupItem>
        <ToggleGroupItem value="yearly" className="px-3">
          {s.yearly}
        </ToggleGroupItem>
      </ToggleGroup>
      <Button disabled={busy || disabled} onClick={() => onCheckout(period)}>
        {busy ? <Spinner /> : <Sparkles />} {s.upgrade}
      </Button>
    </div>
  );
}

/**
 * Something that needs Pro, while it does ([locked]): [children], one element drawn with its own
 * lock, says [reason] on hover or focus. Choosing it opens the Pro dialog (`useOverlay("pro")`).
 */
export function Locked({ locked, reason, children }: { locked: boolean; reason: string; children: ReactElement }) {
  if (!locked) return children;
  return (
    <Tooltip>
      <TooltipTrigger asChild>{children}</TooltipTrigger>
      <TooltipContent side="right">{reason}</TooltipContent>
    </Tooltip>
  );
}

/** Pro, offered over any page (`?pro=editors`) by a feature shown [Locked]. Mounted once. */
export function ProOverlay() {
  const pro = useOverlay("pro");
  return pro.value ? <ProDialog feature={pro.value} onClose={pro.close} /> : null;
}

/** Why Pro is offered, what it adds, and paying for it; signed out, signing in first. */
function ProDialog({ feature, onClose }: { feature: string; onClose: () => void }) {
  const [st, pro] = useScreen(() => app.pro(feature), [feature]);
  const me = useApp();
  const { pathname, search } = useLocation();
  // With Pro already (signed in just now, say), there's nothing to offer.
  const has = me?.pro;
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => void (has && onClose()), [has]);
  if (!st || !me || me.pro) return null;
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <span className="mx-auto mb-1 grid size-10 place-items-center rounded-full bg-primary/10 text-primary sm:mx-0">
            <Sparkles className="size-5" />
          </span>
          <DialogTitle>{s.proTitle}</DialogTitle>
          <DialogDescription>{st.reason}</DialogDescription>
        </DialogHeader>
        <ProFeatures />
        {me.signedIn ? (
          <Checkout className="border-t pt-4" busy={st.busy} onCheckout={(period) => pro.checkout(period, go)} />
        ) : (
          <Button className="justify-self-end" asChild>
            <Link to={`/sign-in?next=${encodeURIComponent(pathname + search)}`}>{s.signIn}</Link>
          </Button>
        )}
      </DialogContent>
    </Dialog>
  );
}
