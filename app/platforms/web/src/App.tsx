import { useEffect } from "react";
import { Navigate, Route, Routes } from "react-router";
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
import { Settings } from "@/screens/Settings";
import { Batch, CasePage, Cases, Promo, StaffEntry, StaffLog, StaffSearch, Team } from "@/screens/Staff";
import { Redeem } from "@/screens/Redeem";
import { Security } from "@/screens/Security";
import { Study } from "@/screens/Study";
import { TemplatePage, Templates } from "@/screens/Templates";

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
          <Route path="profile" element={<Settings />} />
          <Route path="profile/templates" element={<Templates />} />
          <Route path="profile/templates/:id" element={<TemplatePage />} />
          <Route path="profile/issues" element={<Issues />} />
          <Route path="profile/security" element={<Security />} />
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
        <Route path="settings" element={<Navigate to="/profile" replace />} />
        <Route path="templates" element={<Navigate to="/profile/templates" replace />} />
        <Route path="issues" element={<Navigate to="/profile/issues" replace />} />
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
