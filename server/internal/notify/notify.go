// Package notify tells people about their account: a notification the next device that opens
// shows once (a modal or a toast), and the same words by email or push where its kind wants them.
// The server writes every word, so a new kind needs no app update.
package notify

import (
	"context"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/riverqueue/river"

	"github.com/sourcelocation/rondo/server/internal/mail"
	"github.com/sourcelocation/rondo/server/internal/ory"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// Presentation is how the apps show a notification.
type Presentation int16

const (
	// Elsewhere: only by email or push; the apps don't show it.
	Elsewhere Presentation = iota
	Toast
	Modal
)

func (p Presentation) String() string {
	switch p {
	case Modal:
		return "modal"
	case Toast:
		return "toast"
	default:
		return "none"
	}
}

// Message is one notification, in the words people see.
type Message struct {
	Kind         string
	Presentation Presentation
	Title, Body  string
	// A link to open from it: a path on Rondo's origin (/app/…, /docs/…).
	LinkLabel, LinkPath string
	Email               bool
	// Push: also to the devices that receive pushes.
	Push bool
}

type Sender struct {
	Jobs *river.Client[pgx.Tx]
}

// Send records [m] for [user] inside [tx] and queues its email with it, so both happen or neither.
func (s *Sender) Send(ctx context.Context, tx pgx.Tx, user uuid.UUID, m Message) error {
	err := store.New(tx).InsertNotification(ctx, store.InsertNotificationParams{
		ID: uuid.Must(uuid.NewV7()), UserID: user, Kind: m.Kind, Presentation: int16(m.Presentation),
		Title: m.Title, Body: m.Body, LinkLabel: optional(m.LinkLabel), LinkUrl: optional(m.LinkPath),
	})
	if err != nil {
		return err
	}
	if m.Email {
		if _, err := s.Jobs.InsertTx(ctx, tx, Email{User: user, Message: m}, nil); err != nil {
			return err
		}
	}
	if m.Push {
		path := m.LinkPath
		if path == "" {
			path = "/app/"
		}
		_, err = s.Jobs.InsertTx(ctx, tx, Push{User: user, Payload: Payload{Title: m.Title, Body: m.Body, Path: path}}, nil)
	}
	return err
}

func optional(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// Email sends a notification to the address its person signs in with, looked up when it's sent.
type Email struct {
	User    uuid.UUID `json:"user"`
	Message Message   `json:"message"`
}

func (Email) Kind() string { return "notify_email" }

type EmailWorker struct {
	river.WorkerDefaults[Email]
	Ory       *ory.Client
	Mailer    *mail.Mailer
	PublicURL string
}

func (w *EmailWorker) Work(ctx context.Context, job *river.Job[Email]) error {
	who, err := w.Ory.Identity(ctx, job.Args.User)
	if err != nil {
		return err
	}
	if who == nil {
		return nil
	}
	m := job.Args.Message
	out := mail.Message{To: who.Email, Subject: m.Title, Heading: m.Title, Text: m.Body}
	if m.LinkPath != "" {
		out.Link, out.LinkLabel = w.PublicURL+m.LinkPath, m.LinkLabel
	}
	return w.Mailer.Send(ctx, out)
}
