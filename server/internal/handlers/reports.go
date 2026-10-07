package handlers

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"slices"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/problem"
	"github.com/sourcelocation/rondo/server/internal/staff"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// Reports and the cases they gather into.

var (
	reportReasons = []string{"spam", "harassment", "hate", "sexual", "impersonation", "illegal", "other"}
	laws          = []string{"copyright", "child_safety", "terrorism", "privacy", "other_law"}
)

// reportsPerDay caps how much one person can report.
const reportsPerDay = 20

// weight is what someone's report counts: less from new accounts, more from people whose reports
// led to action, nothing from restricted people.
func (s *Server) weight(ctx context.Context, u store.User) (float32, error) {
	if active(u.RestrictedUntil) || active(u.BannedUntil) {
		return 0, nil
	}
	age := float32(1)
	switch since := time.Since(u.CreatedAt); {
	case since < 24*time.Hour:
		age = 0.2
	case since < 30*24*time.Hour:
		age = 0.5
	}
	record, err := s.Q.ReporterRecord(ctx, u.ID)
	if err != nil {
		return 0, err
	}
	return age * float32(record.Upheld+1) / float32(record.Resolved+2) * 2, nil
}

func (s *Server) Report(ctx context.Context, r api.ReportRequestObject) (api.ReportResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	b := r.Body
	if !slices.Contains(reportReasons, b.Reason) {
		return nil, problem.Invalid("Pick what's wrong.")
	}
	if b.Reason == "illegal" {
		if b.Law == nil || !slices.Contains(laws, *b.Law) || b.Note == nil || len(strings.TrimSpace(*b.Note)) < 10 || b.GoodFaith == nil || !*b.GoodFaith {
			return nil, problem.Invalid("For illegal content, say which law, explain why, and confirm it's in good faith.")
		}
	}
	reporter, err := s.user(ctx, id.ID)
	if err != nil {
		return nil, err
	}
	lately, err := s.Q.ReportsLately(ctx, id.ID)
	if err != nil {
		return nil, err
	}
	if lately >= reportsPerDay {
		return nil, fail(http.StatusTooManyRequests, api.RateLimited, "That's a lot of reports for one day. Try again tomorrow.")
	}
	about, threshold := int16(staff.AboutPerson), float32(3)
	if b.TargetKind == api.ReportRequestTargetKindDeck {
		about = staff.AboutDeck
		deck, err := s.Q.DeckBrief(ctx, b.TargetId)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, errNotFound
		}
		if err != nil {
			return nil, err
		}
		if deck.OwnerID != nil && *deck.OwnerID == id.ID {
			return nil, problem.Invalid("That's your own deck.")
		}
		followers, err := s.Q.Followers(ctx, b.TargetId)
		if err != nil {
			return nil, err
		}
		threshold += float32(followers) / 200
	} else {
		if _, err := s.Q.User(ctx, b.TargetId); errors.Is(err, pgx.ErrNoRows) {
			return nil, errNotFound
		}
		if b.TargetId == id.ID {
			return nil, problem.Invalid("That's you.")
		}
	}
	weight, err := s.weight(ctx, reporter)
	if err != nil {
		return nil, err
	}
	err = s.tx(ctx, func(tx pgx.Tx) error {
		q := store.New(tx)
		c, err := q.ReportsCase(ctx, store.ReportsCaseParams{ID: uuid.Must(uuid.NewV7()), TargetKind: &about, TargetID: &b.TargetId})
		if err != nil {
			return err
		}
		added, err := q.AddReport(ctx, store.AddReportParams{
			ID: uuid.Must(uuid.NewV7()), ReporterID: id.ID, CaseID: c.ID, Reason: b.Reason, Law: b.Law, Note: b.Note, Weight: weight,
		})
		if err != nil {
			return err
		}
		if added == 0 {
			return fail(http.StatusConflict, api.Conflict, "You reported this already; moderators will look at it.")
		}
		_, err = q.ScoreCase(ctx, store.ScoreCaseParams{Threshold: threshold, ID: c.ID})
		return err
	})
	if err != nil {
		return nil, err
	}
	return api.Report204Response{}, nil
}

// flagPublishing applies the rules that need no report, after someone publishes: an account under
// a day old publishing more than three decks, or five from one address in a day.
func (s *Server) flagPublishing(ctx context.Context, u store.User) error {
	if time.Since(u.CreatedAt) < 24*time.Hour {
		n, err := s.Q.PublishedBy(ctx, &u.ID)
		if err != nil {
			return err
		}
		if n > 3 {
			if err := s.Staff.Flag(ctx, staff.AboutPerson, u.ID, "new_account_publishing"); err != nil {
				return err
			}
		}
	}
	if ip := client(ctx).IP; ip.IsValid() {
		n, err := s.Q.PublishedFrom(ctx, ip)
		if err != nil {
			return err
		}
		if n >= 5 {
			return s.Staff.Flag(ctx, staff.AboutPerson, u.ID, "address_publishing")
		}
	}
	return nil
}

// Staff's queue -------------------------------------------------------------------------------------

var caseKinds = map[int16]string{staff.CaseReports: "reports", staff.CaseWave: "wave", staff.CaseFlag: "flag"}

func (s *Server) caseDecks(ctx context.Context, ids []uuid.UUID) ([]api.CaseDeck, error) {
	out := make([]api.CaseDeck, 0, len(ids))
	for _, id := range ids {
		d, err := s.Q.DeckBrief(ctx, id)
		if errors.Is(err, pgx.ErrNoRows) {
			continue
		}
		if err != nil {
			return nil, err
		}
		item := api.CaseDeck{Id: d.ID, Name: d.Name, Slug: d.Slug}
		if d.OwnerID != nil {
			refs, err := s.refs(ctx, *d.OwnerID)
			if err != nil {
				return nil, err
			}
			owner := refs[*d.OwnerID]
			item.Owner = &owner
		}
		out = append(out, item)
	}
	return out, nil
}

func (s *Server) people(ctx context.Context, ids []uuid.UUID) ([]api.UserRef, error) {
	refs, err := s.refs(ctx, ids...)
	if err != nil {
		return nil, err
	}
	out := make([]api.UserRef, 0, len(ids))
	for _, id := range ids {
		out = append(out, refs[id])
	}
	return out, nil
}

func (s *Server) caseItem(ctx context.Context, c store.Case, reports int, reasons []string) (api.CaseItem, error) {
	item := api.CaseItem{
		Id: c.ID, Kind: caseKinds[c.Kind], Lane: c.Lane, Score: c.Score, Reports: reports, Reasons: reasons, ResolvedAt: c.ResolvedAt,
	}
	if c.OpenedAt != nil {
		item.OpenedAt = *c.OpenedAt
	}
	if c.TargetID != nil && c.TargetKind != nil {
		if *c.TargetKind == staff.AboutDeck {
			decks, err := s.caseDecks(ctx, []uuid.UUID{*c.TargetID})
			if err != nil {
				return item, err
			}
			if len(decks) > 0 {
				item.Deck = &decks[0]
			}
		} else {
			refs, err := s.refs(ctx, *c.TargetID)
			if err != nil {
				return item, err
			}
			person := refs[*c.TargetID]
			item.Person = &person
		}
	}
	switch c.Kind {
	case staff.CaseWave:
		var w staff.Wave
		if err := json.Unmarshal(c.Data, &w); err != nil {
			return item, err
		}
		item.Summary, item.Today, item.Usual = &w.Metric, &w.Today, ptrTo(float32(w.Median))
	case staff.CaseFlag:
		var f map[string]string
		if err := json.Unmarshal(c.Data, &f); err != nil {
			return item, err
		}
		rule := f["rule"]
		item.Summary = &rule
	}
	return item, nil
}

func ptrTo[T any](v T) *T { return &v }

func (s *Server) reviewer(ctx context.Context) error {
	_, a, err := s.access(ctx)
	if err != nil {
		return err
	}
	if a.Locked() {
		return staff.ErrPasskey
	}
	if !a.Can(staff.CasesReview) {
		return problem.Forbidden
	}
	return nil
}

func (s *Server) ListCases(ctx context.Context, r api.ListCasesRequestObject) (api.ListCasesResponseObject, error) {
	if err := s.reviewer(ctx); err != nil {
		return nil, err
	}
	var lane *string
	if r.Params.Lane != nil {
		l := string(*r.Params.Lane)
		lane = &l
	}
	var target *int16
	if r.Params.Target != nil {
		t := int16(staff.AboutPerson)
		if *r.Params.Target == api.ListCasesParamsTargetDeck {
			t = staff.AboutDeck
		}
		target = &t
	}
	rows, err := s.Q.OpenCases(ctx, store.OpenCasesParams{Lane: lane, Target: target})
	if err != nil {
		return nil, err
	}
	out := api.ListCases200JSONResponse{}
	for _, r := range rows {
		c := store.Case{
			ID: r.ID, Kind: r.Kind, TargetKind: r.TargetKind, TargetID: r.TargetID, Lane: r.Lane, Score: r.Score, OpenedAt: r.OpenedAt,
			ResolvedAt: r.ResolvedAt, ResolvedBy: r.ResolvedBy, Outcome: r.Outcome, Data: r.Data, CreatedAt: r.CreatedAt,
		}
		item, err := s.caseItem(ctx, c, int(r.Reports), r.Reasons)
		if err != nil {
			return nil, err
		}
		out = append(out, item)
	}
	return out, nil
}

func (s *Server) GetCase(ctx context.Context, r api.GetCaseRequestObject) (api.GetCaseResponseObject, error) {
	if err := s.reviewer(ctx); err != nil {
		return nil, err
	}
	c, err := s.Q.CaseByID(ctx, r.CaseId)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, errNotFound
	}
	if err != nil {
		return nil, err
	}
	reports, err := s.Q.CaseReports(ctx, c.ID)
	if err != nil {
		return nil, err
	}
	var reasons []string
	var reporters []uuid.UUID
	for _, rep := range reports {
		if !slices.Contains(reasons, rep.Reason) {
			reasons = append(reasons, rep.Reason)
		}
		reporters = append(reporters, rep.ReporterID)
	}
	item, err := s.caseItem(ctx, c, len(reports), append([]string{}, reasons...))
	if err != nil {
		return nil, err
	}
	refs, err := s.refs(ctx, reporters...)
	if err != nil {
		return nil, err
	}
	out := api.GetCase200JSONResponse{Item: item, Reports: []api.ReportItem{}, Decks: []api.CaseDeck{}, People: []api.UserRef{}, Reporters: []api.UserRef{}}
	for _, rep := range reports {
		out.Reports = append(out.Reports, api.ReportItem{
			Reporter: refs[rep.ReporterID], Reason: rep.Reason, Law: rep.Law, Note: rep.Note, Weight: rep.Weight, CreatedAt: rep.CreatedAt,
		})
	}
	if c.Kind == staff.CaseWave {
		var w staff.Wave
		if err := json.Unmarshal(c.Data, &w); err != nil {
			return nil, err
		}
		if out.Decks, err = s.caseDecks(ctx, w.Decks); err != nil {
			return nil, err
		}
		if out.People, err = s.people(ctx, w.People); err != nil {
			return nil, err
		}
		if out.Reporters, err = s.people(ctx, w.Reporters); err != nil {
			return nil, err
		}
	}
	return out, nil
}
