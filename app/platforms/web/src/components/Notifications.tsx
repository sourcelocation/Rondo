import { useEffect } from "react";
import { useNavigate } from "react-router";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { app, s, useApp, type NotificationItem } from "@/rondo";

/** Opens a notification's link: inside the app, or (the docs) in a new tab. */
function useOpen() {
  const navigate = useNavigate();
  return (n: NotificationItem) => {
    if (!n.link) return;
    if (n.link.startsWith("/app/")) navigate(n.link.slice("/app".length));
    else window.open(n.link, "_blank", "noopener,noreferrer");
  };
}

/**
 * The server's notifications, each shown once on whichever device opens next: a dialog for those
 * that wait for the person, a toast for the rest. Mounted once.
 */
export function Notifications() {
  const n = useApp()?.notification ?? null;
  const open = useOpen();
  useEffect(() => {
    if (!n || n.modal) return;
    toast(n.title, {
      description: n.body || undefined,
      action: n.link && n.linkLabel ? { label: n.linkLabel, onClick: () => open(n) } : undefined,
    });
    app.seen(n.id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [n?.id]);
  if (!n?.modal) return null;
  return (
    <Dialog open onOpenChange={(o) => !o && app.seen(n.id)}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{n.title}</DialogTitle>
          {n.body && <DialogDescription>{n.body}</DialogDescription>}
        </DialogHeader>
        <DialogFooter>
          {n.link && n.linkLabel && (
            <Button variant="ghost" onClick={() => (open(n), app.seen(n.id))}>
              {n.linkLabel}
            </Button>
          )}
          <Button onClick={() => app.seen(n.id)}>{s.gotIt}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Reads the account again whenever the app comes back to the front: new roles, Pro, notifications. */
export function useRefreshOnFocus() {
  useEffect(() => {
    const refresh = () => document.visibilityState === "visible" && app.refresh();
    document.addEventListener("visibilitychange", refresh);
    return () => document.removeEventListener("visibilitychange", refresh);
  }, []);
}
