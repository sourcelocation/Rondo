// Package jobs runs Rondo's background work on River: sending mail, building exports, removing
// media no note uses any more, forgetting what's kept only for a while, refreshing Discover,
// removing lost second steps when they're due, and telling Stripe a customer's new email.
package jobs

import (
	"archive/zip"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/riverqueue/river"

	"github.com/sourcelocation/rondo/server/internal/blob"
	"github.com/sourcelocation/rondo/server/internal/mail"
	"github.com/sourcelocation/rondo/server/internal/ory"
	"github.com/sourcelocation/rondo/server/internal/store"
)

type Mail struct{ mail.Message }

func (Mail) Kind() string { return "mail" }

// CustomerEmail sends Stripe's receipts and invoices to someone's new address.
type CustomerEmail struct {
	Customer string `json:"customer"`
	Email    string `json:"email"`
}

func (CustomerEmail) Kind() string { return "customer_email" }

type CustomerEmailWorker struct {
	river.WorkerDefaults[CustomerEmail]
	// Stripe is dawl's Stripe gateway; nil when Stripe isn't set up.
	Stripe interface {
		SetCustomerEmail(ctx context.Context, customer, email string) error
	}
}

func (w *CustomerEmailWorker) Work(ctx context.Context, job *river.Job[CustomerEmail]) error {
	if w.Stripe == nil {
		return nil
	}
	return w.Stripe.SetCustomerEmail(ctx, job.Args.Customer, job.Args.Email)
}

type MailWorker struct {
	river.WorkerDefaults[Mail]
	Mailer *mail.Mailer
}

// Work sends the message, unless it's outlived its Lifespan while sending failed: a code that has
// run out is better not sent.
func (w *MailWorker) Work(ctx context.Context, job *river.Job[Mail]) error {
	if life := job.Args.Lifespan; life > 0 && time.Since(job.CreatedAt) > life {
		return river.JobCancel(fmt.Errorf("not sent within %s", life))
	}
	return w.Mailer.Send(ctx, job.Args.Message)
}

type Export struct {
	User  uuid.UUID `json:"user"`
	Email string    `json:"email"`
}

func (Export) Kind() string { return "export" }

// ExportWorker writes everything a person owns into one ZIP, a Rondo file as the API's Manifest
// describes it: rondo.json, a JSON file per table, and their media.
type ExportWorker struct {
	river.WorkerDefaults[Export]
	Pool  *pgxpool.Pool
	Blobs blob.Store
	// Jobs queues the email with the link, which retries on its own: a failed send doesn't build
	// the export again.
	Jobs *river.Client[pgx.Tx]
}

// The tables an export holds, each row with the columns a Rondo file has: those the sync server's
// records name. A column only the server keeps never leaves it.
var exported = []struct{ file, query string }{
	{"decks.json", "SELECT id, owner_id, parent_id, name, position, icon, color, description, language, new_per_day, reviews_per_day, retention, gated, unlock_at, v, deleted_at FROM decks WHERE owner_id = $1 ORDER BY id"},
	{"templates.json", "SELECT id, owner_id, name, kind, def, v, deleted_at FROM templates WHERE owner_id = $1 ORDER BY id"},
	{"notes.json", "SELECT id, owner_id, deck_id, template_id, fields, v, deleted_at, tags FROM notes WHERE owner_id = $1 ORDER BY id"},
	{"events.json", "SELECT id, user_id, subject_id, card, kind, value, at, duration_ms, due, stability, difficulty, reps, lapses FROM events WHERE user_id = $1 ORDER BY id"},
	{"settings.json", "SELECT user_id, grading, theme, text_size, timezone, fsrs_weights, v, reminder_at FROM settings WHERE user_id = $1"},
	{"smart_decks.json", "SELECT id, owner_id, name, position, icon, color, filter, new_per_day, reviews_per_day, v, deleted_at FROM smart_decks WHERE owner_id = $1 ORDER BY id"},
}

// Manifest is a Rondo file's rondo.json: its format, that it's an account's, its media's types by
// hash, and when it was made.
type Manifest struct {
	Format     int               `json:"format"`
	Kind       string            `json:"kind"`
	Media      map[string]string `json:"media,omitempty"`
	ExportedAt int64             `json:"exported_at"`
}

func (w *ExportWorker) Work(ctx context.Context, job *river.Job[Export]) error {
	token := make([]byte, 24)
	_, _ = rand.Read(token)
	key := "exports/" + hex.EncodeToString(token) + ".zip"
	pr, pw := io.Pipe()
	go func() { pw.CloseWithError(w.Write(ctx, job.Args.User, pw)) }()
	if err := w.Blobs.Put(ctx, key, "application/zip", pr); err != nil {
		return err
	}
	url, err := w.Blobs.URL(ctx, key, 7*24*time.Hour)
	if err != nil {
		return err
	}
	_, err = w.Jobs.Insert(ctx, Mail{Message: mail.Export(job.Args.Email, url)}, nil)
	return err
}

// Write writes [user]'s export to [out].
func (w *ExportWorker) Write(ctx context.Context, user uuid.UUID, out io.Writer) error {
	z := zip.NewWriter(out)
	for _, t := range exported {
		f, err := z.Create(t.file)
		if err != nil {
			return err
		}
		if err := w.table(ctx, f, t.query, user); err != nil {
			return fmt.Errorf("export %s: %w", t.file, err)
		}
	}
	q := store.New(w.Pool)
	hashes, err := q.OwnMedia(ctx, &user)
	if err != nil {
		return err
	}
	types := map[string]string{}
	for _, h := range hashes {
		m, err := q.Media(ctx, h)
		if err != nil || !m.Verified {
			continue
		}
		name := hex.EncodeToString(h)
		r, err := w.Blobs.Open(ctx, "media/"+name)
		if err != nil {
			continue
		}
		f, err := z.Create("media/" + name)
		if err == nil {
			_, err = io.Copy(f, r)
		}
		r.Close()
		if err != nil {
			return err
		}
		types[name] = m.Mime
	}
	manifest := Manifest{Format: 1, Kind: "account", ExportedAt: time.Now().UnixMilli(), Media: types}
	f, err := z.Create("rondo.json")
	if err != nil {
		return err
	}
	if err := json.NewEncoder(f).Encode(manifest); err != nil {
		return err
	}
	return z.Close()
}

// table writes the rows [query] finds as a JSON array, a row at a time: each as an object of its
// columns, without those that are null.
func (w *ExportWorker) table(ctx context.Context, out io.Writer, query string, user uuid.UUID) error {
	rows, err := w.Pool.Query(ctx, "SELECT jsonb_strip_nulls(to_jsonb(x)) FROM ("+query+") x", user)
	if err != nil {
		return err
	}
	defer rows.Close()
	sep := "["
	for rows.Next() {
		var raw []byte
		if err := rows.Scan(&raw); err != nil {
			return err
		}
		if _, err := io.WriteString(out, sep); err != nil {
			return err
		}
		if _, err := out.Write(raw); err != nil {
			return err
		}
		sep = ","
	}
	if err := rows.Err(); err != nil {
		return err
	}
	if sep == "[" {
		_, err = io.WriteString(out, "[]")
	} else {
		_, err = io.WriteString(out, "]")
	}
	return err
}

type MediaGC struct{}

func (MediaGC) Kind() string { return "media_gc" }

// MediaGCWorker removes files no note has used for a week.
type MediaGCWorker struct {
	river.WorkerDefaults[MediaGC]
	Pool  *pgxpool.Pool
	Blobs blob.Store
}

func (w *MediaGCWorker) Work(ctx context.Context, _ *river.Job[MediaGC]) error {
	q := store.New(w.Pool)
	unused, err := q.UnusedMedia(ctx, time.Now().Add(-7*24*time.Hour))
	if err != nil {
		return err
	}
	for _, h := range unused {
		if err := w.Blobs.Delete(ctx, "media/"+hex.EncodeToString(h)); err != nil {
			return err
		}
		if err := q.DeleteMedia(ctx, h); err != nil {
			return err
		}
	}
	return nil
}

// Cleanup forgets, daily, what Rondo keeps only for a while: addresses unseen for 90 days, and
// notifications shown more than 90 days ago.
type Cleanup struct{}

func (Cleanup) Kind() string { return "cleanup" }

type CleanupWorker struct {
	river.WorkerDefaults[Cleanup]
	Pool *pgxpool.Pool
}

func (w *CleanupWorker) Work(ctx context.Context, _ *river.Job[Cleanup]) error {
	q := store.New(w.Pool)
	if err := q.ForgetOldIPs(ctx); err != nil {
		return err
	}
	return q.ForgetSeenNotifications(ctx)
}

// RefreshDiscover brings Discover's copy of every listing up to date: names, languages and authors
// as their decks have them now, and counts (followers, notes, follows this week for Hot).
type RefreshDiscover struct{}

func (RefreshDiscover) Kind() string { return "refresh_discover" }

type RefreshDiscoverWorker struct {
	river.WorkerDefaults[RefreshDiscover]
	Pool *pgxpool.Pool
}

func (w *RefreshDiscoverWorker) Work(ctx context.Context, _ *river.Job[RefreshDiscover]) error {
	return store.New(w.Pool).RefreshPublications(ctx, nil)
}

// SecondFactorReset removes someone's second step (authenticator app and recovery codes) a week
// after they asked, unless signing in with it cancelled that meanwhile.
type SecondFactorReset struct {
	User uuid.UUID `json:"user"`
}

func (SecondFactorReset) Kind() string { return "second_factor_reset" }

type SecondFactorResetWorker struct {
	river.WorkerDefaults[SecondFactorReset]
	Pool *pgxpool.Pool
	Ory  *ory.Client
	// Jobs queues the email saying it's done.
	Jobs *river.Client[pgx.Tx]
}

func (w *SecondFactorResetWorker) Work(ctx context.Context, job *river.Job[SecondFactorReset]) error {
	q := store.New(w.Pool)
	reset, err := q.Reset(ctx, job.Args.User)
	if errors.Is(err, pgx.ErrNoRows) || (err == nil && (reset.DueAt == nil || reset.DueAt.After(time.Now()))) {
		return nil
	}
	if err != nil {
		return err
	}
	for _, kind := range []string{"totp", "lookup_secret"} {
		if err := w.Ory.DeleteCredential(ctx, job.Args.User, kind, ""); err != nil {
			return err
		}
	}
	if err := w.Ory.EndSessions(ctx, job.Args.User); err != nil {
		return err
	}
	if err := q.EndReset(ctx, job.Args.User); err != nil {
		return err
	}
	who, err := w.Ory.Identity(ctx, job.Args.User)
	if err != nil || who == nil {
		return err
	}
	_, err = w.Jobs.Insert(ctx, Mail{Message: mail.ResetDone(who.Email)}, nil)
	return err
}
