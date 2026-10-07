package notify

import (
	"context"
	"encoding/json"
	"net/http"

	webpush "github.com/SherClockHolmes/webpush-go"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/riverqueue/river"

	"github.com/sourcelocation/rondo/server/internal/store"
)

// Platforms a device receives pushes on. Only the web's is sent from here yet; the apps' (APNs,
// FCM) arrive with the apps.
const (
	WebPush = 1
	APNs    = 2
	FCM     = 3
)

// Payload is what a push shows: a title, a line, and where tapping it goes (a path on Rondo's origin).
type Payload struct {
	Title string `json:"title"`
	Body  string `json:"body"`
	Path  string `json:"path"`
}

// Pusher sends Web Push messages, signed with Rondo's VAPID keys.
type Pusher struct {
	PublicKey, PrivateKey, Subject string
	HTTP                           *http.Client
}

// Ready says whether Web Push is set up.
func (p *Pusher) Ready() bool { return p != nil && p.PublicKey != "" && p.PrivateKey != "" }

// Push sends [payload] to one device. [gone] says the push service no longer knows it, so it should
// be forgotten.
func (p *Pusher) Push(ctx context.Context, d store.PushDevice, payload Payload) (gone bool, err error) {
	if !p.Ready() || d.Platform != WebPush || d.P256dh == nil || d.Auth == nil {
		return false, nil
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return false, err
	}
	res, err := webpush.SendNotificationWithContext(ctx, body, &webpush.Subscription{
		Endpoint: d.Endpoint, Keys: webpush.Keys{P256dh: *d.P256dh, Auth: *d.Auth},
	}, &webpush.Options{
		Subscriber: p.Subject, VAPIDPublicKey: p.PublicKey, VAPIDPrivateKey: p.PrivateKey, TTL: 12 * 3600, HTTPClient: p.HTTP,
	})
	if err != nil {
		return false, err
	}
	defer res.Body.Close()
	return res.StatusCode == http.StatusNotFound || res.StatusCode == http.StatusGone, nil
}

// Push sends a notification to every device of its person that receives pushes.
type Push struct {
	User    uuid.UUID `json:"user"`
	Payload Payload   `json:"payload"`
}

func (Push) Kind() string { return "notify_push" }

type PushWorker struct {
	river.WorkerDefaults[Push]
	Pool   *pgxpool.Pool
	Pusher *Pusher
}

func (w *PushWorker) Work(ctx context.Context, job *river.Job[Push]) error {
	q := store.New(w.Pool)
	devices, err := q.PushDevices(ctx, job.Args.User)
	if err != nil {
		return err
	}
	for _, d := range devices {
		if err := Deliver(ctx, q, w.Pusher, d, job.Args.Payload); err != nil {
			return err
		}
	}
	return nil
}

// Deliver pushes to one device, forgetting it when the push service says it's gone.
func Deliver(ctx context.Context, q *store.Queries, p *Pusher, d store.PushDevice, payload Payload) error {
	gone, err := p.Push(ctx, d, payload)
	switch {
	case err != nil:
		return err
	case gone:
		return q.ForgetPushDevice(ctx, d.ID)
	default:
		return q.PushOK(ctx, d.ID)
	}
}
