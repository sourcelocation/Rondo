package handlers

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/sourcelocation/rondo/server/internal/api"
)

func profile(ctx context.Context, s *Server, b api.ProfileUpdate) (api.Me, error) {
	res, err := s.UpdateProfile(ctx, api.UpdateProfileRequestObject{Body: &b})
	if err != nil {
		return api.Me{}, err
	}
	return api.Me(res.(api.UpdateProfile200JSONResponse)), nil
}

func TestUsernames(t *testing.T) {
	s := server(t)
	alice, bob := uuid.Must(uuid.NewV7()), uuid.Must(uuid.NewV7())
	me := meOfCtx(as(alice), t, s)
	if !strings.HasPrefix(*me.Username, "user") || *me.ProfileConfirmed {
		t.Fatalf("new accounts get a username from Rondo: %v %v", *me.Username, *me.ProfileConfirmed)
	}
	if _, err := profile(as(alice), s, api.ProfileUpdate{Username: ptr("@Alice "), Confirm: ptr(true)}); err != nil {
		t.Fatalf("the first choice: %v", err)
	}
	if _, err := profile(as(alice), s, api.ProfileUpdate{Username: ptr("alice_two")}); code(err) != api.TooSoon {
		t.Fatalf("then once a month: %v", err)
	}
	for name, want := range map[string]api.ErrorCode{"alice": api.UsernameTaken, "admin": api.UsernameInvalid, "rondo_fan": api.UsernameInvalid, "a": api.UsernameInvalid, "user123": api.UsernameInvalid} {
		if _, err := profile(as(bob), s, api.ProfileUpdate{Username: &name}); code(err) != want {
			t.Errorf("%s: %v, want %s", name, err, want)
		}
	}
	if _, err := s.Pool.Exec(context.Background(), "UPDATE users SET username_changed_at = now() - interval '31 days' WHERE id = $1", alice); err != nil {
		t.Fatal(err)
	}
	if _, err := profile(as(alice), s, api.ProfileUpdate{Username: ptr("alice_two")}); err != nil {
		t.Fatalf("a month later: %v", err)
	}
	check, err := s.CheckUsername(as(bob), api.CheckUsernameRequestObject{Username: "alice"})
	c := check.(api.CheckUsername200JSONResponse)
	if err != nil || c.Available || *c.Reason != "held" || c.Suggestion == nil || !strings.HasPrefix(*c.Suggestion, "alice") {
		t.Fatalf("an old username stays its owner's for a month: %+v %v", c, err)
	}
	found, err := s.GetProfile(context.Background(), api.GetProfileRequestObject{Username: "alice"})
	p := found.(api.GetProfile200JSONResponse)
	if err != nil || p.Username != "alice_two" || p.MovedFrom == nil {
		t.Fatalf("and leads to its new one: %+v %v", p, err)
	}
	if _, err := profile(as(bob), s, api.ProfileUpdate{Flag: ptr("xx")}); code(err) != api.Invalid {
		t.Fatalf("flags are countries: %v", err)
	}
	if me, err := profile(as(bob), s, api.ProfileUpdate{Flag: ptr("pl"), Name: ptr("  Bob   Smith ")}); err != nil || *me.Flag != "PL" || *me.Name != "Bob Smith" {
		t.Fatalf("flag and name: %+v %v", me, err)
	}
}

func TestSharingNeedsAConfirmedProfile(t *testing.T) {
	s := server(t)
	carol := person(t, s, "", false)
	d := deck(t, s, carol, "Kanji")
	if _, err := s.PublishDeck(as(carol), api.PublishDeckRequestObject{DeckId: d}); code(err) != api.ProfileRequired {
		t.Fatalf("publishing: %v", err)
	}
	if _, err := profile(as(carol), s, api.ProfileUpdate{Confirm: ptr(true)}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.PublishDeck(as(carol), api.PublishDeckRequestObject{DeckId: d}); err != nil {
		t.Fatalf("after confirming: %v", err)
	}
}

func TestActivityIsCountedInTheLearnersDays(t *testing.T) {
	s := server(t)
	ctx := context.Background()
	dana := person(t, s, "Dana", false)
	if _, err := s.Pool.Exec(ctx, "INSERT INTO settings (user_id, timezone, v, seq) VALUES ($1, 'Asia/Tokyo', 1, 1)", dana); err != nil {
		t.Fatal(err)
	}
	tokyo, _ := time.LoadLocation("Asia/Tokyo")
	now := time.Now().In(tokyo)
	today := time.Date(now.Year(), now.Month(), now.Day(), 12, 0, 0, 0, tokyo)
	if now.Hour() < 4 {
		today = today.AddDate(0, 0, -1)
	}
	review := func(at time.Time) uuid.UUID {
		id := uuid.Must(uuid.NewV7())
		if _, err := s.Pool.Exec(ctx, "INSERT INTO events (id, user_id, subject_id, kind, at, duration_ms, seq) VALUES ($1, $2, $1, 1, $3, 60000, 1)",
			id, dana, at.UnixMilli()); err != nil {
			t.Fatal(err)
		}
		return id
	}
	review(today)
	review(today.AddDate(0, 0, -1))
	review(today.AddDate(0, 0, -1).Add(-8*time.Hour + 30*time.Minute)) // 4:30 am that day: still that day
	review(today.AddDate(0, 0, -2).Add(15 * time.Hour))                // 3 am the next morning: still two days ago
	undone := review(today.AddDate(0, 0, -5))
	if _, err := s.Pool.Exec(ctx, "INSERT INTO events (id, user_id, subject_id, kind, at, seq) VALUES ($1, $2, $3, 8, $4, 1)",
		uuid.Must(uuid.NewV7()), dana, undone, today.UnixMilli()); err != nil {
		t.Fatal(err)
	}
	review(today.AddDate(0, 0, -9))
	username := meOfCtx(as(dana), t, s).Username
	res, err := s.GetProfile(ctx, api.GetProfileRequestObject{Username: *username})
	if err != nil {
		t.Fatal(err)
	}
	a := res.(api.GetProfile200JSONResponse).Activity
	if a.Reviews != 5 || a.Streak != 3 || a.LongestStreak != 3 || a.YearMinutes > 5 {
		t.Fatalf("activity: reviews %d, streak %d, longest %d, minutes %d", a.Reviews, a.Streak, a.LongestStreak, a.YearMinutes)
	}
	activities.Purge()
	if _, err := profile(as(dana), s, api.ProfileUpdate{ActivityHidden: ptr(true)}); err != nil {
		t.Fatal(err)
	}
	res, _ = s.GetProfile(ctx, api.GetProfileRequestObject{Username: *username})
	if res.(api.GetProfile200JSONResponse).Activity != nil {
		t.Fatal("hidden activity isn't shown")
	}
}
