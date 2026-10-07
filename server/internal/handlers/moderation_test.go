package handlers

import (
	"context"
	"fmt"
	"net/netip"
	"slices"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/sourcelocation/dawl/subscription"

	"github.com/sourcelocation/rondo/server/internal/api"
)

// from is a request from [ip].
func from(ctx context.Context, ip string) context.Context {
	return context.WithValue(ctx, clientKey{}, Client{IP: netip.MustParseAddr(ip)})
}

func act(ctx context.Context, t *testing.T, s *Server, r api.ActionRequest) (api.StaffAction, error) {
	t.Helper()
	res, err := s.DoAction(ctx, api.DoActionRequestObject{Body: &r})
	if err != nil {
		return api.StaffAction{}, err
	}
	return api.StaffAction(res.(api.DoAction201JSONResponse)), nil
}

func undo(ctx context.Context, t *testing.T, s *Server, id uuid.UUID) {
	t.Helper()
	if _, err := s.UndoAction(ctx, api.UndoActionRequestObject{ActionId: id, Body: &api.UndoRequest{Reason: "mistake"}}); err != nil {
		t.Fatalf("undo: %v", err)
	}
}

func kinds(ctx context.Context, t *testing.T, s *Server) []string {
	t.Helper()
	var out []string
	for _, n := range *meOfCtx(ctx, t, s).Notifications {
		out = append(out, n.Kind)
	}
	return out
}

func TestRestrictionsStopWhatReachesOthers(t *testing.T) {
	s := server(t)
	owner, bob := person(t, s, "Owner", false), person(t, s, "Bob", false)
	o := asStaff(owner, ownerEmail)
	d := deck(t, s, bob, "Verbs")
	if _, err := s.PublishDeck(as(bob), api.PublishDeckRequestObject{DeckId: d}); err != nil {
		t.Fatal(err)
	}
	week := time.Now().Add(7 * 24 * time.Hour)
	restrict, err := act(o, t, s, api.ActionRequest{Kind: "user.restrict", UserId: &bob, Reason: ptr("spam"), Until: &week})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.PublishDeck(as(bob), api.PublishDeckRequestObject{DeckId: d}); code(err) != api.Restricted {
		t.Fatalf("restricted people don't publish: %v", err)
	}
	if found, _ := s.SearchDiscover(context.Background(), api.SearchDiscoverRequestObject{}); len(found.(api.SearchDiscover200JSONResponse).Items) != 0 {
		t.Fatal("their decks leave Discover while it lasts")
	}
	username := *meOfCtx(as(bob), t, s).Username
	if _, err := s.GetProfile(context.Background(), api.GetProfileRequestObject{Username: username}); code(err) != api.NotFound {
		t.Fatalf("and their profile: %v", err)
	}
	if me := meOfCtx(as(bob), t, s); me.RestrictedUntil == nil || !slices.Contains(kinds(as(bob), t, s), "restricted") {
		t.Fatalf("they're told, and the app knows: %v %v", me.RestrictedUntil, kinds(as(bob), t, s))
	}
	undo(o, t, s, restrict.Id)
	if _, err := s.PublishDeck(as(bob), api.PublishDeckRequestObject{DeckId: d}); err != nil {
		t.Fatalf("undone, they publish again: %v", err)
	}
	if found, _ := s.SearchDiscover(context.Background(), api.SearchDiscoverRequestObject{}); len(found.(api.SearchDiscover200JSONResponse).Items) != 1 {
		t.Fatal("and their deck is back")
	}
	if !slices.Contains(kinds(as(bob), t, s), "lifted") {
		t.Fatal("and hear so")
	}
}

// renewals is a store that lets the server turn renewal off and on, and remembers what it was told.
type renewals struct{ calls []string }

func (r *renewals) SetAutoRenew(_ context.Context, ref string, on bool) error {
	r.calls = append(r.calls, fmt.Sprintf("%s %v", ref, on))
	return nil
}

func TestBansReachKratosHydraStoresAndMail(t *testing.T) {
	s, fake := serverWithOry(t)
	owner, mod, bob := person(t, s, "Owner", false), person(t, s, "Mo", false), person(t, s, "Bob", false)
	stripe := &renewals{}
	s.Staff.Renewers = map[subscription.Provider]subscription.Renewer{subscription.Stripe: stripe}
	end := time.Now().Add(30 * 24 * time.Hour)
	for _, sub := range []subscription.State{
		{Provider: subscription.Stripe, ProviderRef: "sub_bob", Status: subscription.StatusActive, AutoRenew: true},
		{Provider: subscription.AppStore, ProviderRef: "2000000001", Status: subscription.StatusActive, AutoRenew: true},
	} {
		sub.CurrentPeriodEnd, sub.Account = &end, bob.String()
		if err := s.Save(context.Background(), sub); err != nil {
			t.Fatal(err)
		}
	}
	o := asStaff(owner, ownerEmail)
	if _, err := grant(o, t, s, mod, "moderator"); err != nil {
		t.Fatal(err)
	}
	m := asStaff(mod, "mo@example.com")
	ban, err := act(m, t, s, api.ActionRequest{Kind: "user.ban", UserId: &bob, Reason: ptr("harassment"), Note: ptr("Repeated insults.")})
	if err != nil {
		t.Fatalf("moderators ban: %v", err)
	}
	for _, call := range []string{"PATCH /admin/identities/" + bob.String(), "DELETE /admin/identities/" + bob.String() + "/sessions", "DELETE /admin/oauth2/auth/sessions/consent?all=true&subject=" + bob.String()} {
		if !fake.called(call) {
			t.Errorf("a ban calls %s", call)
		}
	}
	u, _ := s.Q.User(context.Background(), bob)
	if u.BannedUntil == nil || u.BannedUntil.Before(time.Now().AddDate(100, 0, 0)) {
		t.Fatalf("for good: %v", u.BannedUntil)
	}
	var mails int
	_ = s.Pool.QueryRow(context.Background(), "SELECT count(*) FROM river_job WHERE kind = 'notify_email' AND args->'message'->>'Kind' = 'banned'").Scan(&mails)
	if mails != 1 {
		t.Fatalf("the reason goes by email: %d", mails)
	}
	if !slices.Equal(stripe.calls, []string{"sub_bob false"}) {
		t.Fatalf("renewal stops where the store allows it: %v", stripe.calls)
	}
	// Stripe then reports the subscription as ending; the ban still knows it stopped it.
	canceled := subscription.State{Provider: subscription.Stripe, ProviderRef: "sub_bob", Status: subscription.StatusCanceled, CurrentPeriodEnd: &end, Account: bob.String()}
	if err := s.Save(context.Background(), canceled); err != nil {
		t.Fatal(err)
	}
	if _, err := act(m, t, s, api.ActionRequest{Kind: "user.ban", UserId: &owner, Reason: ptr("spam")}); code(err) != api.Forbidden {
		t.Fatalf("never above you: %v", err)
	}
	undo(m, t, s, ban.Id)
	if u, _ := s.Q.User(context.Background(), bob); u.BannedUntil != nil {
		t.Fatalf("undone: %v", u.BannedUntil)
	}
	if !slices.Equal(stripe.calls, []string{"sub_bob false", "sub_bob true"}) {
		t.Fatalf("undoing the ban turns back on what it turned off: %v", stripe.calls)
	}
	if stopped, _ := s.Q.StoppedRenewals(context.Background(), bob); len(stopped) != 0 {
		t.Fatalf("and forgets it: %v", stopped)
	}
}

func TestNameResetsCanBeUndone(t *testing.T) {
	s := server(t)
	owner, bob := person(t, s, "Owner", false), uuid.Must(uuid.NewV7())
	if _, err := profile(as(bob), s, api.ProfileUpdate{Username: ptr("bobby"), Name: ptr("Bob")}); err != nil {
		t.Fatal(err)
	}
	o := asStaff(owner, ownerEmail)
	reset, err := act(o, t, s, api.ActionRequest{Kind: "user.rename", UserId: &bob, Reason: ptr("name")})
	if err != nil {
		t.Fatal(err)
	}
	me := meOfCtx(as(bob), t, s)
	if *me.Username == "bobby" || me.Name != nil || me.UsernameChangesAt != nil {
		t.Fatalf("reset, and free to pick another at once: %v %v %v", *me.Username, me.Name, me.UsernameChangesAt)
	}
	undo(o, t, s, reset.Id)
	if me := meOfCtx(as(bob), t, s); *me.Username != "bobby" || *me.Name != "Bob" {
		t.Fatalf("undone: %v %v", *me.Username, me.Name)
	}
}

func TestTakingADeckDown(t *testing.T) {
	s := server(t)
	owner, alice, bob := person(t, s, "Owner", false), person(t, s, "Alice", false), person(t, s, "Bob", false)
	o := asStaff(owner, ownerEmail)
	d := deck(t, s, alice, "Scans of a textbook")
	res, err := s.PublishDeck(as(alice), api.PublishDeckRequestObject{DeckId: d})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.FollowDeck(as(bob), api.FollowDeckRequestObject{Slug: res.(api.PublishDeck200JSONResponse).Slug}); err != nil {
		t.Fatal(err)
	}
	down, err := act(o, t, s, api.ActionRequest{Kind: "deck.remove", DeckId: &d, Reason: ptr("copyright"), Data: &map[string]string{"take_down": "true"}})
	if err != nil {
		t.Fatal(err)
	}
	if shares, _ := s.Q.Shares(context.Background(), d); len(shares) != 0 {
		t.Fatal("taking down ends sharing")
	}
	if !slices.Contains(kinds(as(bob), t, s), "followed_taken_down") || !slices.Contains(kinds(as(alice), t, s), "deck_removed") {
		t.Fatal("the follower and the author are told")
	}
	if _, err := s.PublishDeck(as(alice), api.PublishDeckRequestObject{DeckId: d}); code(err) != api.Forbidden {
		t.Fatalf("it can't be listed again: %v", err)
	}
	undo(o, t, s, down.Id)
	if shares, _ := s.Q.Shares(context.Background(), d); len(shares) != 1 {
		t.Fatal("undoing shares it again")
	}
	if found, _ := s.SearchDiscover(context.Background(), api.SearchDiscoverRequestObject{}); len(found.(api.SearchDiscover200JSONResponse).Items) != 1 {
		t.Fatal("and lists it")
	}
}

func TestNetworksAndAccountsThatBelongTogether(t *testing.T) {
	s := server(t)
	owner, mod := person(t, s, "Owner", false), person(t, s, "Mo", false)
	o := asStaff(owner, ownerEmail)
	if _, err := grant(o, t, s, mod, "moderator"); err != nil {
		t.Fatal(err)
	}
	a, b, c := uuid.Must(uuid.NewV7()), uuid.Must(uuid.NewV7()), uuid.Must(uuid.NewV7())
	if _, err := profile(from(as(a), "203.0.113.9"), s, api.ProfileUpdate{Username: ptr("spammer_king")}); err != nil {
		t.Fatal(err)
	}
	if _, err := profile(from(as(b), "203.0.113.9"), s, api.ProfileUpdate{Confirm: ptr(true)}); err != nil {
		t.Fatal(err)
	}
	if _, err := profile(from(as(c), "198.51.100.7"), s, api.ProfileUpdate{Username: ptr("spammer_kingg")}); err != nil {
		t.Fatal(err)
	}
	res, err := s.GetStaffUser(o, api.GetStaffUserRequestObject{UserId: a})
	if err != nil {
		t.Fatal(err)
	}
	why := map[uuid.UUID][]string{}
	for _, r := range *res.(api.GetStaffUser200JSONResponse).Related {
		why[r.User.Id] = r.Why
	}
	if !slices.Contains(why[b], "address") || !slices.Contains(why[c], "name") {
		t.Fatalf("related: %v", why)
	}
	modView, _ := s.GetStaffUser(asStaff(mod, "mo@example.com"), api.GetStaffUserRequestObject{UserId: a})
	if v := modView.(api.GetStaffUser200JSONResponse); v.Related != nil || v.Ips != nil || v.Email != nil || v.Names != nil {
		t.Fatal("moderators don't see private details")
	}
	if _, err := act(asStaff(mod, "mo@example.com"), t, s, api.ActionRequest{Kind: "network.restrict", Data: &map[string]string{"range": "203.0.113.0/24"}}); code(err) != api.Forbidden {
		t.Fatalf("moderators don't restrict networks: %v", err)
	}
	if _, err := act(o, t, s, api.ActionRequest{Kind: "network.restrict", Data: &map[string]string{"range": "10.0.0.0/8"}}); code(err) != api.Invalid {
		t.Fatalf("nor anyone that wide: %v", err)
	}
	if _, err := act(o, t, s, api.ActionRequest{Kind: "network.restrict", Data: &map[string]string{"range": "203.0.113.0/24"}}); err != nil {
		t.Fatal(err)
	}
	if _, err := profile(from(as(b), "203.0.113.20"), s, api.ProfileUpdate{Name: ptr("B")}); code(err) != api.NetworkRestricted {
		t.Fatalf("nothing social from there: %v", err)
	}
	if _, err := profile(from(as(b), "198.51.100.7"), s, api.ProfileUpdate{Name: ptr("B")}); err != nil {
		t.Fatalf("elsewhere it's fine: %v", err)
	}
}

func TestTheLadderSuggestsTheNextStep(t *testing.T) {
	s := server(t)
	owner, bob := person(t, s, "Owner", false), person(t, s, "Bob", false)
	o := asStaff(owner, ownerEmail)
	steps := []string{"user.warn", "user.warn", "user.restrict", "user.restrict", "user.restrict", "user.ban"}
	for i, want := range steps {
		res, err := s.GetStaffUser(o, api.GetStaffUserRequestObject{UserId: bob})
		if err != nil {
			t.Fatal(err)
		}
		if got := res.(api.GetStaffUser200JSONResponse).SuggestedKind; got != want {
			t.Fatalf("step %d: %s, want %s", i, got, want)
		}
		if _, err := act(o, t, s, api.ActionRequest{Kind: "user.warn", UserId: &bob, Reason: ptr("spam")}); err != nil {
			t.Fatal(err)
		}
	}
}
