package staff

import (
	"context"
	"encoding/json"
	"errors"
	"slices"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/riverqueue/river"

	"github.com/sourcelocation/rondo/server/internal/notify"
	"github.com/sourcelocation/rondo/server/internal/problem"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// Cases: what reaches staff. A staff action with a case resolves it (dismissing is its own kind), so
// a case's outcome is derived from the log like everything else, and undoing reopens it.

// Case kinds and what they're about.
const (
	CaseReports = 1
	CaseWave    = 2
	CaseFlag    = 3

	AboutDeck   = 1
	AboutPerson = 2
)

// Cases: a case is resolved by the first entry with it that isn't undone; reporters hear once it is.
var Cases = Area{"case", func(ctx context.Context, s *Service, tx pgx.Tx, e *Entry) error {
	q := store.New(tx)
	before, err := q.CaseByID(ctx, *e.CaseID)
	if err != nil {
		return err
	}
	after, err := q.DeriveCase(ctx, *e.CaseID)
	if err != nil || before.ResolvedAt != nil || after.ResolvedAt == nil || after.Kind != CaseReports {
		return err
	}
	reporters, err := q.CaseReporters(ctx, after.ID)
	if err != nil {
		return err
	}
	for _, r := range reporters {
		if err := s.Notify.Send(ctx, tx, r, notify.Reviewed()); err != nil {
			return err
		}
	}
	return nil
}}

func init() {
	register(&Kind{
		Name: "case.dismiss", Permission: CasesReview, Limit: 120, Undoable: true,
		Check: func(_ context.Context, _ *Service, _ *Access, e *Entry) error {
			if e.CaseID == nil {
				return problem.Invalid("Say which case.")
			}
			return nil
		},
	})
}

// Spikes looks, every few hours, for a day that stands out: reports, publications or new accounts
// far above the last four weeks (more than the median plus five times the median deviation, and at
// least ten). Each opens one wave case a day, with what's behind it.
type Spikes struct{}

func (Spikes) Kind() string { return "spikes" }

type SpikesWorker struct {
	river.WorkerDefaults[Spikes]
	Staff *Service
}

// Wave is what a wave case holds: the measure, today's count against the usual, and who's behind it.
type Wave struct {
	Metric    string      `json:"metric"`
	Today     int         `json:"today"`
	Median    float64     `json:"median"`
	Decks     []uuid.UUID `json:"decks,omitempty"`
	People    []uuid.UUID `json:"people,omitempty"`
	Reporters []uuid.UUID `json:"reporters,omitempty"`
}

func (w *SpikesWorker) Work(ctx context.Context, _ *river.Job[Spikes]) error {
	q := store.New(w.Staff.Pool)
	windows, err := q.Windows(ctx)
	if err != nil || len(windows) < 2 {
		return err
	}
	series := map[string][]int{}
	for _, d := range windows {
		series["reports"] = append(series["reports"], int(d.Reports))
		series["publications"] = append(series["publications"], int(d.Publications))
		series["signups"] = append(series["signups"], int(d.Signups))
	}
	for metric, counts := range series {
		today, past := counts[len(counts)-1], counts[:len(counts)-1]
		median, high := Spike(past, today)
		if !high {
			continue
		}
		seen, err := q.WaveLately(ctx, metric)
		if err != nil {
			return err
		}
		if seen {
			continue
		}
		wave := Wave{Metric: metric, Today: today, Median: median}
		if err := w.behind(ctx, q, &wave); err != nil {
			return err
		}
		data, err := json.Marshal(wave)
		if err != nil {
			return err
		}
		if _, err := q.AddCase(ctx, store.AddCaseParams{ID: uuid.Must(uuid.NewV7()), Kind: CaseWave, Lane: "waves", Data: data}); err != nil {
			return err
		}
	}
	return nil
}

// behind lists what made a wave: the decks and people reported most and who reported them, or the
// decks just published.
func (w *SpikesWorker) behind(ctx context.Context, q *store.Queries, wave *Wave) error {
	switch wave.Metric {
	case "reports":
		targets, err := q.ReportedLately(ctx)
		if err != nil {
			return err
		}
		for _, t := range targets {
			if t.TargetID == nil || t.TargetKind == nil {
				continue
			}
			if *t.TargetKind == AboutDeck {
				wave.Decks = append(wave.Decks, *t.TargetID)
			} else {
				wave.People = append(wave.People, *t.TargetID)
			}
		}
		reporters, err := q.ReportersLately(ctx)
		if err != nil {
			return err
		}
		for _, r := range reporters {
			wave.Reporters = append(wave.Reporters, r.ReporterID)
		}
	case "publications":
		decks, err := q.PublishedLately(ctx)
		if err != nil {
			return err
		}
		wave.Decks = decks
	}
	return nil
}

// Spike says whether [today] stands far out of [past]: above the median plus five times the median
// absolute deviation (at least one), and at least ten.
func Spike(past []int, today int) (median float64, high bool) {
	median = middle(past)
	deviations := make([]int, len(past))
	for i, n := range past {
		deviations[i] = int(abs(float64(n) - median))
	}
	spread := max(middle(deviations), 1)
	return median, today >= 10 && float64(today) > median+5*spread
}

func middle(xs []int) float64 {
	if len(xs) == 0 {
		return 0
	}
	sorted := slices.Sorted(slices.Values(xs))
	n := len(sorted)
	if n%2 == 1 {
		return float64(sorted[n/2])
	}
	return float64(sorted[n/2-1]+sorted[n/2]) / 2
}

func abs(x float64) float64 {
	if x < 0 {
		return -x
	}
	return x
}

// Flag opens a case about a deck or person a rule noticed, unless one is open already.
func (s *Service) Flag(ctx context.Context, about int16, target uuid.UUID, rule string) error {
	q := store.New(s.Pool)
	open, err := q.OpenFlag(ctx, store.OpenFlagParams{TargetKind: &about, TargetID: &target})
	if err != nil || open {
		return err
	}
	data, err := json.Marshal(map[string]string{"rule": rule})
	if err != nil {
		return err
	}
	_, err = q.AddCase(ctx, store.AddCaseParams{
		ID: uuid.Must(uuid.NewV7()), Kind: CaseFlag, TargetKind: &about, TargetID: &target, Lane: "flags", Data: data,
	})
	return err
}

var errNoCase = problem.NotFound

func (s *Service) caseExists(ctx context.Context, id uuid.UUID) error {
	_, err := store.New(s.Pool).CaseByID(ctx, id)
	if errors.Is(err, pgx.ErrNoRows) {
		return errNoCase
	}
	return err
}
