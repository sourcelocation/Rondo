import {
  BarChart3,
  BookOpen,
  ChevronsUpDown,
  CloudOff,
  Compass,
  Ellipsis,
  ExternalLink,
  Eye,
  EyeOff,
  House,
  LogOut,
  Plus,
  RefreshCw,
  Search,
  SettingsIcon,
  Shield,
  Sparkles,
  TriangleAlert,
  UserRound,
  type LucideIcon,
} from "lucide-react";
import { useState } from "react";
import { Link, Outlet } from "react-router";
import { DeckTree, HomeContext, NewDeckDialog, SmartDecks } from "@/components/decks";
import { Avatar, confirm, docs, Wordmark } from "@/components/kit";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Popover, PopoverContent, PopoverDescription, PopoverTitle, PopoverTrigger } from "@/components/ui/popover";
import { app, s, useApp, useScreen, type SyncProblem } from "@/rondo";
import { placeLink, useOverlay, usePlace } from "@/routing";
import { cn } from "cn";

/** The places; Settings is in the account menu, at the foot of the sidebar or the top of a phone's screen. */
const nav: { to: string; label: string; icon: LucideIcon }[] = [
  { to: "/", label: s.home, icon: House },
  { to: "/browse", label: s.browse, icon: Search },
  { to: "/discover", label: s.discover, icon: Compass },
  { to: "/progress", label: s.insights, icon: BarChart3 },
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
  const st = useApp();
  const created = useOverlay("new");
  const place = usePlace();
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
                to={placeLink(to, place)}
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
            {home[0] && home[0].smart.length > 0 && (
              <>
                <div className="mt-5 mb-1 px-2 text-xs font-medium tracking-wider text-muted-foreground uppercase">
                  {s.smartDecks}
                </div>
                <SmartDecks items={home[0].smart} compact />
              </>
            )}
          </div>
          <div className="grid gap-1 border-t p-3">
            <SyncLine />
            <AccountMenu />
          </div>
        </aside>
        <header className="sticky top-0 z-20 flex h-14 items-center justify-between gap-3 border-b bg-background/90 px-4 backdrop-blur md:hidden">
          <Link to="/">
            <Wordmark />
          </Link>
          <div className="flex items-center gap-1">
            <SyncBadge />
            <AccountMenu compact />
          </div>
        </header>
        <main className="mx-auto max-w-[var(--rd-size-content-max)] px-4 pt-6 pb-28 sm:px-8 md:pt-10 md:pb-16">
          <Outlet />
        </main>
        <nav className="fixed inset-x-0 bottom-0 z-20 flex border-t bg-surface pb-[env(safe-area-inset-bottom)] md:hidden">
          {nav.map(({ to, label, icon: Icon }) => (
            <Link
              key={to}
              to={placeLink(to, place)}
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

const line = "flex items-center gap-2 rounded-md px-2 py-1 text-left text-xs hover:text-foreground";

/**
 * Whether changes are reaching the server, or why not, in a few words that open the rest; and changes
 * the server didn't take. Everything works offline either way, so none of it stands in the page.
 */
function SyncLine() {
  const st = useApp();
  const [open, setOpen] = useState(false);
  if (!st?.signedIn) return null;
  const minutes = st.syncedAt ? Math.round((app.now() - st.syncedAt) / 60_000) : null;
  const ago =
    minutes == null || minutes < 1
      ? s.synced
      : `${s.synced} · ${new Intl.RelativeTimeFormat(undefined, { numeric: "auto" }).format(-minutes, "minute")}`;
  return (
    <>
      {st.sync && !st.syncing ? (
        <Popover open={open} onOpenChange={setOpen}>
          <PopoverTrigger asChild>
            <button
              type="button"
              className={cn(line, st.sync.needsYou ? "text-warning-foreground" : "text-muted-foreground")}
            >
              <CloudOff className="size-3.5 shrink-0" />
              {st.sync.label}
            </button>
          </PopoverTrigger>
          <PopoverContent side="top" align="start">
            <Problem problem={st.sync} done={() => setOpen(false)} />
          </PopoverContent>
        </Popover>
      ) : (
        <button
          type="button"
          className={cn(line, "text-muted-foreground")}
          onClick={() => app.syncNow()}
          title={s.syncNow}
        >
          <RefreshCw className={cn("size-3.5 shrink-0", st.syncing && "animate-spin")} />
          {st.syncing ? s.syncing : st.syncedAt ? ago : s.syncNow}
        </button>
      )}
      {st.issues > 0 && (
        <Link to="/settings/issues" className={cn(line, "text-warning-foreground")}>
          <TriangleAlert className="size-3.5 shrink-0" />
          {s.issueCount(st.issues)}
        </Link>
      )}
    </>
  );
}

/** The sync line's place on a phone's header: an icon, only while something's wrong, opening what. */
function SyncBadge() {
  const st = useApp();
  const [open, setOpen] = useState(false);
  if (!st?.signedIn || (!st.sync && st.issues === 0)) return null;
  const Icon = st.issues > 0 ? TriangleAlert : CloudOff;
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          aria-label={st.sync?.label ?? s.issueCount(st.issues)}
          className={st.issues > 0 || st.sync?.needsYou ? "text-warning-foreground" : "text-muted-foreground"}
        >
          <Icon />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="grid gap-4">
        {st.sync && <Problem problem={st.sync} done={() => setOpen(false)} />}
        {st.issues > 0 && (
          <div className="grid gap-3 text-sm">
            <div className="grid gap-1">
              <PopoverTitle>{s.issueCount(st.issues)}</PopoverTitle>
              <PopoverDescription>{s.issues(st.issues)}</PopoverDescription>
            </div>
            <Button size="sm" variant="secondary" className="justify-self-start" asChild>
              <Link to="/settings/issues" onClick={() => setOpen(false)}>
                {s.syncIssues}
              </Link>
            </Button>
          </div>
        )}
      </PopoverContent>
    </Popover>
  );
}

/** Why syncing stopped, in full, and what resumes it: signing in again, or trying again. */
function Problem({ problem, done }: { problem: SyncProblem; done: () => void }) {
  return (
    <div className="grid gap-3 text-sm">
      <div className="grid gap-1">
        <PopoverTitle>{problem.label}</PopoverTitle>
        <PopoverDescription>{problem.message}</PopoverDescription>
      </div>
      {problem.signIn ? (
        <Button size="sm" className="justify-self-start" asChild>
          <Link to="/sign-in" onClick={done}>
            {s.signIn}
          </Link>
        </Button>
      ) : (
        <Button size="sm" variant="secondary" className="justify-self-start" onClick={() => (done(), app.syncNow())}>
          {s.retry}
        </Button>
      )}
    </div>
  );
}

/** Settings and the docs: in the account menu, signed in or not. */
function SettingsAndDocs() {
  const place = usePlace();
  return (
    <>
      <DropdownMenuItem asChild>
        <Link to={placeLink("/settings", place)}>
          <SettingsIcon /> {s.settings}
        </Link>
      </DropdownMenuItem>
      <DropdownMenuItem asChild>
        <a href={docs()} target="_blank" rel="noopener noreferrer">
          <BookOpen /> {s.documentation}
          <ExternalLink className="ml-auto" />
        </a>
      </DropdownMenuItem>
    </>
  );
}

/**
 * Who's signed in, and a menu: their profile, Settings, upgrading to Pro, the docs and signing out.
 * Signed out, a way to sign in beside a smaller menu. [compact]: just the picture, for a phone's header.
 */
function AccountMenu({ compact }: { compact?: boolean }) {
  const st = useApp();
  const place = usePlace();
  const pro = useOverlay("pro");
  const side = compact ? "bottom" : "top";
  if (!st) return null;
  if (!st.signedIn)
    return (
      <div className="flex items-center gap-1">
        <Button variant={compact ? "ghost" : "secondary"} className={cn(!compact && "flex-1")} asChild>
          <Link to="/sign-in">{s.signIn}</Link>
        </Button>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon" aria-label={s.more}>
              <Ellipsis />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent side={side} align="end" className="w-56">
            <SettingsAndDocs />
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    );
  const name = st.name?.trim() || st.email || s.account;
  const here = place === "/settings";
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        {compact ? (
          <button
            type="button"
            className="-mr-1.5 grid size-11 place-items-center rounded-full outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
            aria-label={`${s.account}: ${name}`}
          >
            <Avatar name={name} />
          </button>
        ) : (
          <button
            type="button"
            className={cn(
              "flex w-full items-center gap-3 rounded-md p-2 text-left outline-none hover:bg-accent/60 focus-visible:ring-[3px] focus-visible:ring-ring/50 data-[state=open]:bg-accent/60",
              here && "bg-accent",
            )}
            aria-label={`${s.account}: ${name}`}
          >
            <Avatar name={name} />
            <span className="min-w-0 flex-1">
              <span className="block truncate text-sm font-medium">{name}</span>
              <span className="block text-xs text-muted-foreground">{st.pro ? s.pro : s.free}</span>
            </span>
            <ChevronsUpDown className="size-4 text-muted-foreground" />
          </button>
        )}
      </DropdownMenuTrigger>
      <DropdownMenuContent side={side} align={compact ? "end" : "start"} className="w-60">
        <DropdownMenuLabel className="grid font-normal">
          <span className="truncate font-medium">{name}</span>
          {st.email && st.email !== name && <span className="truncate text-xs text-muted-foreground">{st.email}</span>}
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        {st.username && (
          <DropdownMenuItem asChild>
            <Link to={`/u/${st.username}`}>
              <UserRound /> {s.yourProfile}
            </Link>
          </DropdownMenuItem>
        )}
        <SettingsAndDocs />
        {!st.pro && (
          <DropdownMenuItem className="text-primary focus:text-primary" onSelect={() => pro.open("upgrade")}>
            <Sparkles className="text-primary" /> {s.upgrade}
          </DropdownMenuItem>
        )}
        {st.roles.length > 0 && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuItem onSelect={() => app.showStaff(!st.staff)}>
              {st.staff ? <EyeOff /> : <Eye />} {st.staff ? s.hideStaff : s.showStaff}
            </DropdownMenuItem>
          </>
        )}
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={signOut}>
          <LogOut /> {s.signOut}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
