// Package reminders reminds people to study, when they turned it on: once a day at their time,
// only when cards are due and they haven't studied yet, on the devices that don't remind them
// themselves, and by email if they chose. After seven ignored in a row, reminders pause until they
// study again.
package reminders

import (
	"context"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/riverqueue/river"

	"github.com/sourcelocation/rondo/server/internal/jobs"
	"github.com/sourcelocation/rondo/server/internal/mail"
	"github.com/sourcelocation/rondo/server/internal/notify"
	"github.com/sourcelocation/rondo/server/internal/ory"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// Every is how often reminders are looked at; a reminder goes within this window after its time.
const Every = 15 * time.Minute

// pauseAfter is how many ignored reminders pause them.
const pauseAfter = 7

// Tick looks at who should be reminded now.
type Tick struct{}

func (Tick) Kind() string { return "reminders" }

type Worker struct {
	river.WorkerDefaults[Tick]
	Pool      *pgxpool.Pool
	Pusher    *notify.Pusher
	Ory       *ory.Client
	Notify    *notify.Sender
	Jobs      *river.Client[pgx.Tx]
	PublicURL string
	// Now is the clock; tests set it.
	Now func() time.Time
}

func (w *Worker) now() time.Time {
	if w.Now != nil {
		return w.Now()
	}
	return time.Now()
}

func (w *Worker) Work(ctx context.Context, _ *river.Job[Tick]) error {
	q := store.New(w.Pool)
	rows, err := q.Reminding(ctx)
	if err != nil {
		return err
	}
	for _, r := range rows {
		if err := w.remind(ctx, q, r); err != nil {
			return err
		}
	}
	return nil
}

// day is a moment's learner day: dates start at 4 am in their time zone.
func day(t time.Time) time.Time {
	t = t.Add(-4 * time.Hour)
	return time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, time.UTC)
}

func (w *Worker) remind(ctx context.Context, q *store.Queries, r store.RemindingRow) error {
	zone := time.UTC
	if r.Timezone != nil {
		if loc, err := time.LoadLocation(*r.Timezone); err == nil {
			zone = loc
		}
	}
	now := w.now().In(zone)
	minutes := now.Hour()*60 + now.Minute()
	at := int(*r.ReminderAt)
	if minutes < at || minutes >= at+int(Every/time.Minute) {
		return nil
	}
	today := day(now)
	if r.LastSentOn != nil && !r.LastSentOn.Before(today) {
		return nil
	}
	var lastReview time.Time
	if r.LastReview > 0 {
		lastReview = time.UnixMilli(r.LastReview)
	}
	studiedToday := !lastReview.IsZero() && !day(lastReview.In(zone)).Before(today)
	if r.PausedAt != nil && lastReview.After(*r.PausedAt) {
		r.PausedAt, r.Ignored = nil, 0
	}
	if studiedToday || r.PausedAt != nil {
		return nil
	}
	// The summary counts days from the day it was sent; -1 when it doesn't reach today.
	due := -1
	if s, err := q.Summary(ctx, r.UserID); err == nil {
		if k := int(today.Sub(day(s.At.In(zone))) / (24 * time.Hour)); k >= 0 && k < len(s.Due) {
			due = int(s.Due[k])
		}
	}
	if due == 0 {
		return nil
	}
	ignored := r.Ignored
	if r.LastSentAt != nil && lastReview.Before(*r.LastSentAt) {
		ignored++
	} else {
		ignored = 0
	}
	state := store.PutReminderStateParams{UserID: r.UserID, LastSentAt: ptr(w.now()), LastSentOn: &today, Ignored: ignored}
	if ignored >= pauseAfter {
		state.PausedAt = ptr(w.now())
		return pgx.BeginFunc(ctx, w.Pool, func(tx pgx.Tx) error {
			if err := store.New(tx).PutReminderState(ctx, state); err != nil {
				return err
			}
			return w.Notify.Send(ctx, tx, r.UserID, notify.RemindersPaused())
		})
	}
	if err := q.PutReminderState(ctx, state); err != nil {
		return err
	}
	title, body := "Time for today's reviews", "A few minutes keeps it all fresh."
	if due > 0 {
		title = strconv.Itoa(due) + " cards are waiting"
		if due == 1 {
			title = "1 card is waiting"
		}
	}
	devices, err := q.PushDevices(ctx, r.UserID)
	if err != nil {
		return err
	}
	for _, d := range devices {
		if !d.Reminders || (d.LocalUntil != nil && d.LocalUntil.After(w.now())) {
			continue
		}
		if err := notify.Deliver(ctx, q, w.Pusher, d, notify.Payload{Title: title, Body: body, Path: "/app/study"}); err != nil {
			return err
		}
	}
	if !r.Email {
		return nil
	}
	who, err := w.Ory.Identity(ctx, r.UserID)
	if err != nil || who == nil {
		return err
	}
	_, err = w.Jobs.Insert(ctx, jobs.Mail{Message: mail.Message{
		To: who.Email, Subject: title, Heading: title, Text: body + " Turn these emails off in Profile › Reminders.",
		Link: w.PublicURL + "/app/study", LinkLabel: "Study now",
	}}, nil)
	return err
}

func ptr[T any](v T) *T { return &v }
