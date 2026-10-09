import { useEffect } from "react";
import { Navigate, Route, Routes, useLocation } from "react-router";
import { toast } from "sonner";
import { Dialogs, NotFound } from "@/components/kit";
import { Notifications, useRefreshOnFocus } from "@/components/Notifications";
import { ProfileSetup } from "@/components/ProfileSetup";
import { Shell } from "@/components/Shell";
import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { app, useTheme } from "@/rondo";
import { useHistoryTracking } from "@/routing";
import { Consent, SignInPage } from "@/screens/Account";
import { Browse } from "@/screens/Browse";
import { Deck } from "@/screens/Deck";
import { Discover, Preview } from "@/screens/Discover";
import { NoteOverlay, NotePage } from "@/screens/Editor";
import { Home } from "@/screens/Home";
import { Import } from "@/screens/Import";
import { Insights } from "@/screens/Insights";
import { Profile } from "@/screens/Profile";
import { Issues } from "@/screens/Issues";
import { Account, Assistants, General, Plan, Reminders, Settings } from "@/screens/Settings";
import { Batch, CasePage, Cases, Promo, StaffEntry, StaffLog, StaffSearch, Team } from "@/screens/Staff";
import { Redeem } from "@/screens/Redeem";
import { Security } from "@/screens/Security";
import { Study } from "@/screens/Study";
import { TemplatePage, Templates } from "@/screens/Templates";

/** Profile's pages moved into Settings; their old addresses, in links and notifications, lead there. */
function Moved() {
  const { pathname, search } = useLocation();
  const to = pathname === "/profile" ? "/settings/account" : pathname.replace(/^\/profile/, "/settings");
  return <Navigate to={to + search} replace />;
}

/**
 * The app's addresses. Each page sits one level below the one its Up button leads to; studying,
 * signing in and agents' consent take the whole window; the note editor opens over any page.
 */
export function App() {
  const theme = useTheme();
  useHistoryTracking();
  useRefreshOnFocus();
  useEffect(() => app.onNotice((m) => toast(m)), []);
  return (
    <TooltipProvider delayDuration={400}>
      <Routes>
        <Route path="study" element={<Study />} />
        <Route path="decks/:id/study" element={<Study />} />
        <Route path="sign-in" element={<SignInPage />} />
        <Route path="oauth/consent" element={<Consent />} />
        <Route element={<Shell />}>
          <Route index element={<Home />} />
          <Route path="decks/:id" element={<Deck />} />
          <Route path="notes/:id" element={<NotePage />} />
          <Route path="import" element={<Import />} />
          <Route path="browse" element={<Browse />} />
          <Route path="discover" element={<Discover />} />
          <Route path="d/:slug" element={<Preview />} />
          <Route path="i/:token" element={<Preview />} />
          <Route path="u/:username" element={<Profile />} />
          <Route path="progress" element={<Insights />} />
          <Route path="settings" element={<Settings />}>
            <Route index element={<General />} />
            <Route path="account" element={<Account />} />
            <Route path="security" element={<Security />} />
            <Route path="plan" element={<Plan />} />
            <Route path="reminders" element={<Reminders />} />
            <Route path="assistants" element={<Assistants />} />
          </Route>
          <Route path="settings/templates" element={<Templates />} />
          <Route path="settings/templates/:id" element={<TemplatePage />} />
          <Route path="settings/issues" element={<Issues />} />
          <Route path="staff" element={<Navigate to="/staff/cases" replace />} />
          <Route path="staff/cases" element={<Cases />} />
          <Route path="staff/cases/:id" element={<CasePage />} />
          <Route path="staff/search" element={<StaffSearch />} />
          <Route path="staff/log" element={<StaffLog />} />
          <Route path="staff/log/:id" element={<StaffEntry />} />
          <Route path="staff/team" element={<Team />} />
          <Route path="staff/promo" element={<Promo />} />
          <Route path="staff/promo/:id" element={<Batch />} />
          <Route path="redeem" element={<Redeem />} />
          <Route path="redeem/:code" element={<Redeem />} />
        </Route>
        {/* Addresses from before, kept working. */}
        <Route path="insights" element={<Navigate to="/progress" replace />} />
        <Route path="profile/*" element={<Moved />} />
        <Route path="templates" element={<Navigate to="/settings/templates" replace />} />
        <Route path="issues" element={<Navigate to="/settings/issues" replace />} />
        <Route path="notes/new" element={<Navigate to="/?note=new" replace />} />
        <Route path="*" element={<NotFound />} />
      </Routes>
      <NoteOverlay />
      <Toaster position="bottom-right" theme={theme} />
      <Dialogs />
      <Notifications />
      <ProfileSetup />
    </TooltipProvider>
  );
}
