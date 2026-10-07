package handlers

import (
	"context"
	"slices"
	"testing"

	"github.com/google/uuid"
	"github.com/riverqueue/river"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/staff"
)

func reporter(t *testing.T, s *Server, old bool) uuid.UUID {
	t.Helper()
	id := person(t, s, "Reporter", false)
	if old {
		if _, err := s.Pool.Exec(context.Background(), "UPDATE users SET created_at = now() - interval '60 days' WHERE id = $1", id); err != nil {
			t.Fatal(err)
		}
	}
	return id
}

func report(ctx context.Context, s *Server, deck uuid.UUID, reason string) error {
	_, err := s.Report(ctx, api.ReportRequestObject{Body: &api.ReportRequest{TargetKind: api.ReportRequestTargetKindDeck, TargetId: deck, Reason: reason}})
	return err
}

func queue(ctx context.Context, t *testing.T, s *Server) []api.CaseItem {
	t.Helper()
	res, err := s.ListCases(ctx, api.ListCasesRequestObject{})
	if err != nil {
		t.Fatal(err)
	}
	return res.(api.ListCases200JSONResponse)
}

func TestReportsReachStaffOnceTheyWeighEnough(t *testing.T) {
	s := server(t)
	owner, alice := person(t, s, "Owner", false), person(t, s, "Alice", false)
	o := asStaff(owner, ownerEmail)
	d := deck(t, s, alice, "Spam deck")
	for range 3 {
		if err := report(as(reporter(t, s, false)), s, d, "spam"); err != nil {
			t.Fatal(err)
		}
	}
	if q := queue(o, t, s); len(q) != 0 {
		t.Fatalf("three new accounts don't open a case: %+v", q)
	}
	veteran := reporter(t, s, true)
	if err := report(as(veteran), s, d, "spam"); err != nil {
		t.Fatal(err)
	}
	if err := report(as(veteran), s, d, "spam"); code(err) != api.Conflict {
		t.Fatalf("once per person: %v", err)
	}
	if err := report(as(reporter(t, s, true)), s, d, "harassment"); err != nil {
		t.Fatal(err)
	}
	if q := queue(o, t, s); len(q) != 0 {
		t.Fatalf("not yet: %+v", q)
	}
	if err := report(as(reporter(t, s, true)), s, d, "spam"); err != nil {
		t.Fatal(err)
	}
	q := queue(o, t, s)
	if len(q) != 1 || q[0].Lane != "safety" || q[0].Reports != 6 || q[0].Deck == nil || q[0].Deck.Name != "Spam deck" {
		t.Fatalf("the case opens, in its most serious lane: %+v", q)
	}
	if err := report(as(alice), s, d, "spam"); code(err) != api.Invalid {
		t.Fatalf("not your own: %v", err)
	}
}

func TestIllegalContentOpensACaseAtOnce(t *testing.T) {
	s := server(t)
	owner, alice := person(t, s, "Owner", false), person(t, s, "Alice", false)
	d := deck(t, s, alice, "Scans")
	r := as(reporter(t, s, false))
	if err := report(r, s, d, "illegal"); code(err) != api.Invalid {
		t.Fatalf("a notice says which law and why: %v", err)
	}
	body := api.ReportRequest{TargetKind: api.ReportRequestTargetKindDeck, TargetId: d, Reason: "illegal",
		Law: ptr("copyright"), Note: ptr("Pages 1 to 300 of my textbook."), GoodFaith: ptr(true)}
	if _, err := s.Report(r, api.ReportRequestObject{Body: &body}); err != nil {
		t.Fatal(err)
	}
	if q := queue(asStaff(owner, ownerEmail), t, s); len(q) != 1 || q[0].Lane != "illegal" {
		t.Fatalf("one notice opens it: %+v", q)
	}
}

func TestResolvingACaseTeachesWhoseReportsCount(t *testing.T) {
	s := server(t)
	owner, alice := person(t, s, "Owner", false), person(t, s, "Alice", false)
	o := asStaff(owner, ownerEmail)
	first, second := deck(t, s, alice, "One"), deck(t, s, alice, "Two")
	reporters := []uuid.UUID{reporter(t, s, true), reporter(t, s, true), reporter(t, s, true)}
	for _, r := range reporters {
		if err := report(as(r), s, first, "spam"); err != nil {
			t.Fatal(err)
		}
	}
	c := queue(o, t, s)[0]
	dismissed, err := act(o, t, s, api.ActionRequest{Kind: "case.dismiss", CaseId: &c.Id})
	if err != nil {
		t.Fatal(err)
	}
	if len(queue(o, t, s)) != 0 || !slices.Contains(kinds(as(reporters[0]), t, s), "report_reviewed") {
		t.Fatal("dismissed, and reporters hear it was looked at")
	}
	for _, r := range reporters {
		if err := report(as(r), s, second, "spam"); err != nil {
			t.Fatal(err)
		}
	}
	if q := queue(o, t, s); len(q) != 0 {
		t.Fatalf("dismissed reports weigh less next time: %+v", q)
	}
	undo(o, t, s, dismissed.Id)
	if q := queue(o, t, s); len(q) != 1 || q[0].Id != c.Id {
		t.Fatalf("undoing reopens it: %+v", q)
	}
	if _, err := act(o, t, s, api.ActionRequest{Kind: "deck.remove", DeckId: &first, Reason: ptr("spam"), CaseId: &c.Id}); err != nil {
		t.Fatal(err)
	}
	got, _ := s.GetCase(o, api.GetCaseRequestObject{CaseId: c.Id})
	if got.(api.GetCase200JSONResponse).Item.ResolvedAt == nil {
		t.Fatal("acting with the case resolves it")
	}
}

func TestWavesAndFlags(t *testing.T) {
	s := server(t)
	ctx := context.Background()
	owner, alice := person(t, s, "Owner", false), person(t, s, "Alice", false)
	if _, high := staff.Spike([]int{1, 2, 0, 3, 1, 2}, 5); high {
		t.Fatal("five isn't a wave")
	}
	if _, high := staff.Spike([]int{1, 2, 0, 3, 1, 2}, 40); !high {
		t.Fatal("forty is")
	}
	for i := range 12 {
		d := deck(t, s, alice, "Deck")
		if err := report(as(reporter(t, s, i%2 == 0)), s, d, "spam"); err != nil {
			t.Fatal(err)
		}
	}
	worker := &staff.SpikesWorker{Staff: s.Staff}
	if err := worker.Work(ctx, &river.Job[staff.Spikes]{}); err != nil {
		t.Fatal(err)
	}
	if err := worker.Work(ctx, &river.Job[staff.Spikes]{}); err != nil {
		t.Fatal(err)
	}
	var waves []api.CaseItem
	for _, c := range queue(asStaff(owner, ownerEmail), t, s) {
		if c.Kind == "wave" {
			waves = append(waves, c)
		}
	}
	if len(waves) != 1 || *waves[0].Summary != "reports" || *waves[0].Today != 12 {
		t.Fatalf("one wave a day: %+v", waves)
	}
	got, _ := s.GetCase(asStaff(owner, ownerEmail), api.GetCaseRequestObject{CaseId: waves[0].Id})
	if w := got.(api.GetCase200JSONResponse); len(w.Decks) != 12 || len(w.Reporters) != 12 {
		t.Fatalf("with what's behind it: %d decks, %d reporters", len(w.Decks), len(w.Reporters))
	}
	fresh := uuid.Must(uuid.NewV7())
	if _, err := profile(as(fresh), s, api.ProfileUpdate{Confirm: ptr(true)}); err != nil {
		t.Fatal(err)
	}
	for range 4 {
		if _, err := s.PublishDeck(as(fresh), api.PublishDeckRequestObject{DeckId: deck(t, s, fresh, "New")}); err != nil {
			t.Fatal(err)
		}
	}
	flags := 0
	for _, c := range queue(asStaff(owner, ownerEmail), t, s) {
		if c.Kind == "flag" && c.Person != nil && c.Person.Id == fresh {
			flags++
		}
	}
	if flags != 1 {
		t.Fatalf("a new account publishing a lot is flagged once: %d", flags)
	}
}
