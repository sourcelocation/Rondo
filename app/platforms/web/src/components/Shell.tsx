import {
  BarChart3,
  ChevronUp,
  CloudOff,
  Compass,
  Eye,
  EyeOff,
  House,
  LifeBuoy,
  LogOut,
  Plus,
  RefreshCw,
  Search,
  Shield,
  TriangleAlert,
  User,
  type LucideIcon,
} from "lucide-react";
import { Link, Outlet, useLocation } from "react-router";
import { DeckTree, HomeContext, NewDeckDialog } from "@/components/decks";
import { confirm, Wordmark } from "@/components/kit";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { app, s, useApp, useScreen } from "@/rondo";
import { placeLink, placeOf, useOverlay } from "@/routing";
import { cn } from "cn";

const nav: { to: string; label: string; icon: LucideIcon }[] = [
  { to: "/", label: s.home, icon: House },
  { to: "/browse", label: s.browse, icon: Search },
  { to: "/discover", label: s.discover, icon: Compass },
  { to: "/progress", label: s.insights, icon: BarChart3 },
  { to: "/profile", label: s.profile, icon: User },
];

/** Signs out, asking first when changes haven't reached the server. */
export const signOut = () =>
  app.signOut(false, async (question) => {
    if (await confirm(s.signOutTitle, question, s.signOutAnyway)) app.signOut(true, () => {});
  });

/** Staff with their tools shown get a place of their own, in the sidebar. */
const staffPlace = { to: "/staff", label: s.staff, icon: Shield };

/** Places, your decks and the account in a sidebar (tabs on phones), around every screen. */
export function Shell() {
  const home = useScreen(() => app.home());
  const { pathname } = useLocation();
  const st = useApp();
  const created = useOverlay("new");
  const place = placeOf(pathname);
  const places = st?.staff && st.roles.length > 0 ? [...nav, staffPlace] : nav;
  return (
    <HomeContext value={home}>
      <div className="min-h-dvh md:pl-[var(--rd-size-sidebar)]">
        <aside className="fixed inset-y-0 left-0 hidden w-[var(--rd-size-sidebar)] flex-col border-r bg-surface md:flex">
          <Link to="/" className="flex h-16 shrink-0 items-center px-5">
            <Wordmark />
          </Link>
          <nav className="grid gap-0.5 px-3">
            {places.map(({ to, label, icon: Icon }) => (
              <Link
                key={to}
                to={placeLink(to, pathname)}
                aria-current={place === to ? "page" : undefined}
                className={cn(
                  "flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium",
                  place === to ? "bg-accent" : "text-subtle hover:bg-accent/60",
                )}
              >
                <Icon className="size-4" />
                {label}
              </Link>
            ))}
          </nav>
          <div className="mt-6 min-h-0 flex-1 overflow-y-auto px-3 pb-4">
            <div className="mb-1 flex items-center justify-between px-2">
              <span className="text-xs font-medium tracking-wider text-muted-foreground uppercase">{s.yourDecks}</span>
              <Button variant="ghost" size="icon-xs" aria-label={s.newDeck} onClick={() => created.open("deck")}>
                <Plus />
              </Button>
            </div>
            {home[0] && <DeckTree items={home[0].mine} compact />}
            {home[0] && home[0].shared.length > 0 && (
              <>
                <div className="mt-5 mb-1 px-2 text-xs font-medium tracking-wider text-muted-foreground uppercase">
                  {s.sharedWithYou}
                </div>
                <DeckTree items={home[0].shared} compact />
              </>
            )}
          </div>
          <div className="grid gap-1 border-t p-3">
            <SyncLine />
            <Account />
          </div>
        </aside>
        <header className="sticky top-0 z-20 flex h-14 items-center border-b bg-background/90 px-4 backdrop-blur md:hidden">
          <Link to="/">
            <Wordmark />
          </Link>
        </header>
        <main className="mx-auto max-w-[var(--rd-size-content-max)] px-4 pt-6 pb-28 sm:px-8 md:pt-10 md:pb-16">
          {(st?.issues ?? 0) > 0 && (
            <Link
              to="/profile/issues"
              className="mb-4 flex items-center gap-3 rounded-lg bg-warning px-4 py-3 text-sm hover:opacity-90"
            >
              <TriangleAlert className="size-4 shrink-0" />
              <span className="flex-1">{s.issues(st!.issues)}</span>
              <span className="font-medium">{s.syncIssues}</span>
            </Link>
          )}
          {st?.sync && st.sync !== s.signedOutSync && (
            <div className="mb-4 flex items-center gap-3 rounded-lg bg-warning px-4 py-3 text-sm">
              <CloudOff className="size-4 shrink-0" />
              <span className="flex-1">{st.sync}</span>
              {st.sync === s.secondFactorDue ? (
                <Button size="sm" variant="ghost" asChild>
                  <Link to="/sign-in">{s.signIn}</Link>
                </Button>
              ) : (
                <Button size="sm" variant="ghost" onClick={() => app.syncNow()}>
                  {s.retry}
                </Button>
              )}
            </div>
          )}
          <Outlet />
        </main>
        <nav className="fixed inset-x-0 bottom-0 z-20 flex border-t bg-surface pb-[env(safe-area-inset-bottom)] md:hidden">
          {nav.map(({ to, label, icon: Icon }) => (
            <Link
              key={to}
              to={placeLink(to, pathname)}
              aria-current={place === to ? "page" : undefined}
              className={cn(
                "flex flex-1 flex-col items-center gap-1 py-2 text-[11px]",
                place === to ? "text-foreground" : "text-muted-foreground",
              )}
            >
              <Icon className="size-5" />
              {label}
            </Link>
          ))}
        </nav>
        <NewDeckDialog />
      </div>
    </HomeContext>
  );
}

/** Whether changes are reaching the server; everything works offline either way. */
function SyncLine() {
  const st = useApp();
  if (!st?.signedIn) return null;
  const minutes = st.syncedAt ? Math.round((app.now() - st.syncedAt) / 60_000) : null;
  const ago =
    minutes == null || minutes < 1
      ? s.synced
      : `${s.synced} · ${new Intl.RelativeTimeFormat(undefined, { numeric: "auto" }).format(-minutes, "minute")}`;
  return (
    <button
      type="button"
      className="flex items-center gap-2 rounded-md px-2 py-1 text-xs text-muted-foreground hover:text-foreground"
      onClick={() => app.syncNow()}
      title={s.syncNow}
    >
      <RefreshCw className={cn("size-3.5", st.syncing && "animate-spin")} />
      {st.syncing ? s.syncing : st.syncedAt ? ago : s.syncNow}
    </button>
  );
}

/** Who's signed in, with the profile, help and signing out; or a way to sign in. */
function Account() {
  const st = useApp();
  if (!st?.signedIn)
    return (
      <Button variant="secondary" asChild>
        <Link to="/sign-in">{s.signIn}</Link>
      </Button>
    );
  const name = st.name?.trim() || st.email || s.account;
  const initials = name
    .split(/[\s@]+/)
    .slice(0, 2)
    .map((p) => p[0])
    .join("")
    .toUpperCase();
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          className="flex w-full items-center gap-3 rounded-md p-2 text-left hover:bg-accent/60"
          aria-label={`${s.account}: ${name}`}
        >
          <span className="grid size-8 shrink-0 place-items-center rounded-full bg-accent text-xs font-medium">
            {initials}
          </span>
          <span className="min-w-0 flex-1">
            <span className="block truncate text-sm font-medium">{name}</span>
            <span className="block text-xs text-muted-foreground">{st.pro ? s.pro : s.free}</span>
          </span>
          <ChevronUp className="size-4 text-muted-foreground" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent side="top" align="start" className="w-56">
        <DropdownMenuItem asChild>
          <Link to="/profile">
            <User /> {s.profile}
          </Link>
        </DropdownMenuItem>
        <DropdownMenuItem asChild>
          <a href={`${location.origin}/docs/`} target="_blank" rel="noopener noreferrer">
            <LifeBuoy /> {s.help}
          </a>
        </DropdownMenuItem>
        {st.roles.length > 0 && (
          <DropdownMenuItem onSelect={() => app.showStaff(!st.staff)}>
            {st.staff ? <EyeOff /> : <Eye />} {st.staff ? s.hideStaff : s.showStaff}
          </DropdownMenuItem>
        )}
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={signOut}>
          <LogOut /> {s.signOut}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
