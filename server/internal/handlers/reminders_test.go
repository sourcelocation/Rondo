package handlers

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"slices"
	"sync/atomic"
	"testing"
	"time"

	webpush "github.com/SherClockHolmes/webpush-go"
	"github.com/google/uuid"
	"github.com/riverqueue/river"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/notify"
	"github.com/sourcelocation/rondo/server/internal/reminders"
)

// browser is a push subscription a test push service counts deliveries to; once [gone], the
// service says it no longer knows it.
func browser(t *testing.T) (endpoint, p256dh, auth string, pushes *atomic.Int32, gone *atomic.Bool) {
	t.Helper()
	pushes, gone = &atomic.Int32{}, &atomic.Bool{}
	service := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		if gone.Load() {
			w.WriteHeader(http.StatusGone)
			return
		}
		pushes.Add(1)
		w.WriteHeader(http.StatusCreated)
	}))
	t.Cleanup(service.Close)
	key, err := ecdh.P256().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	secret := make([]byte, 16)
	_, _ = rand.Read(secret)
	keys := base64.RawURLEncoding
	return service.URL + "/push/1", keys.EncodeToString(key.PublicKey().Bytes()), keys.EncodeToString(secret), pushes, gone
}

func TestReminders(t *testing.T) {
	s := server(t)
	ctx := context.Background()
	private, public, err := webpush.GenerateVAPIDKeys()
	if err != nil {
		t.Fatal(err)
	}
	s.Pusher = &notify.Pusher{PublicKey: public, PrivateKey: private, Subject: "mailto:test@example.com", HTTP: http.DefaultClient}
	ann := person(t, s, "Ann", false)
	now := time.Date(2026, 10, 7, 18, 3, 0, 0, time.UTC)
	if _, err := s.Pool.Exec(ctx, "INSERT INTO settings (user_id, timezone, reminder_at, v, seq) VALUES ($1, 'UTC', 18 * 60, 1, 1)", ann); err != nil {
		t.Fatal(err)
	}
	endpoint, p256dh, auth, pushes, gone := browser(t)
	w := &reminders.Worker{Pool: s.Pool, Pusher: s.Pusher, Ory: s.Ory, Notify: s.Notify, Jobs: s.Jobs, Now: func() time.Time { return now }}
	device := func(localUntil *time.Time) {
		t.Helper()
		if _, err := s.PutPushDevice(as(ann), api.PutPushDeviceRequestObject{Body: &api.PushDevice{
			Platform: api.Web, Endpoint: endpoint, P256dh: &p256dh, Auth: &auth, Reminders: true, LocalUntil: localUntil,
		}}); err != nil {
			t.Fatal(err)
		}
	}
	study := func(at time.Time) {
		t.Helper()
		if _, err := s.Pool.Exec(ctx, "INSERT INTO events (id, user_id, subject_id, kind, at, seq) VALUES ($1, $2, $1, 1, $3, 1)",
			uuid.Must(uuid.NewV7()), ann, at.UnixMilli()); err != nil {
			t.Fatal(err)
		}
	}
	// summary is what the device counted today, as of the test's clock.
	summary := func(due ...int) {
		t.Helper()
		if _, err := s.PutSummary(as(ann), api.PutSummaryRequestObject{Body: &api.Summary{Due: due, Learned: 400}}); err != nil {
			t.Fatal(err)
		}
		if _, err := s.Pool.Exec(ctx, "UPDATE summaries SET at = $2 WHERE user_id = $1", ann, now); err != nil {
			t.Fatal(err)
		}
	}
	tick := func() {
		t.Helper()
		if err := w.Work(ctx, &river.Job[reminders.Tick]{}); err != nil {
			t.Fatal(err)
		}
	}
	summary(12, 30)
	device(nil)
	tick()
	tick()
	if pushes.Load() != 1 {
		t.Fatalf("once a day, at its time: %d pushes", pushes.Load())
	}
	now = now.Add(24 * time.Hour)
	study(now.Add(-time.Hour))
	tick()
	if pushes.Load() != 1 {
		t.Fatal("not after studying today")
	}
	for range 8 {
		now = now.Add(24 * time.Hour)
		tick()
	}
	// The first after studying isn't ignored yet; the seven after it are, and the eighth pauses.
	if got := pushes.Load(); got != 8 {
		t.Fatalf("seven ignored reminders, then a pause: %d pushes", got)
	}
	if !slices.Contains(kinds(as(ann), t, s), "reminders_paused") {
		t.Fatal("and they hear why")
	}
	now = now.Add(24 * time.Hour)
	study(now.Add(-20 * time.Hour))
	tick()
	if pushes.Load() != 9 {
		t.Fatal("studying again resumes them")
	}
	later := now.Add(7 * 24 * time.Hour)
	device(&later)
	now = now.Add(24 * time.Hour)
	tick()
	if pushes.Load() != 9 {
		t.Fatal("not to a device that reminds itself")
	}
	device(nil)
	summary(5, 0)
	now = now.Add(24 * time.Hour)
	tick()
	if pushes.Load() != 9 {
		t.Fatal("not when nothing is due, counting from the day the summary was sent")
	}
	gone.Store(true)
	now = now.Add(24 * time.Hour)
	tick()
	if left, _ := s.Q.PushDevices(ctx, ann); len(left) != 0 {
		t.Fatal("a device the push service forgot is forgotten")
	}
}

func TestRemindersZone(t *testing.T) {
	s := server(t)
	ctx := context.Background()
	private, public, err := webpush.GenerateVAPIDKeys()
	if err != nil {
		t.Fatal(err)
	}
	s.Pusher = &notify.Pusher{PublicKey: public, PrivateKey: private, Subject: "mailto:test@example.com", HTTP: http.DefaultClient}
	bo := person(t, s, "Bo", false)
	if _, err := s.Pool.Exec(ctx, "INSERT INTO settings (user_id, timezone, reminder_at, v, seq) VALUES ($1, 'Asia/Tokyo', 9 * 60, 1, 1)", bo); err != nil {
		t.Fatal(err)
	}
	endpoint, p256dh, auth, pushes, _ := browser(t)
	if _, err := s.PutPushDevice(as(bo), api.PutPushDeviceRequestObject{Body: &api.PushDevice{
		Platform: api.Web, Endpoint: endpoint, P256dh: &p256dh, Auth: &auth, Reminders: true,
	}}); err != nil {
		t.Fatal(err)
	}
	// 9:00 in Tokyo is midnight UTC; the window is the next quarter hour.
	for _, at := range []string{"2026-10-07T23:59:00Z", "2026-10-08T00:15:00Z", "2026-10-08T00:14:00Z"} {
		now, _ := time.Parse(time.RFC3339, at)
		w := &reminders.Worker{Pool: s.Pool, Pusher: s.Pusher, Ory: s.Ory, Notify: s.Notify, Jobs: s.Jobs, Now: func() time.Time { return now }}
		if err := w.Work(ctx, &river.Job[reminders.Tick]{}); err != nil {
			t.Fatal(err)
		}
	}
	if pushes.Load() != 1 {
		t.Fatalf("only within its window, in its time zone: %d pushes", pushes.Load())
	}
}

func TestSummary(t *testing.T) {
	s := server(t)
	ann := person(t, s, "Ann", false)
	for _, due := range [][]int{{}, {1, 2, 3, 4, 5, 6, 7, 8}, {-1}} {
		if _, err := s.PutSummary(as(ann), api.PutSummaryRequestObject{Body: &api.Summary{Due: due}}); err == nil {
			t.Fatalf("%v is refused", due)
		}
	}
	if _, err := s.PutSummary(as(ann), api.PutSummaryRequestObject{Body: &api.Summary{Due: []int{5, 1}, Learned: 12}}); err != nil {
		t.Fatal(err)
	}
	if got, err := s.Q.Summary(context.Background(), ann); err != nil || len(got.Due) != 2 || got.Learned != 12 {
		t.Fatalf("stored: %+v %v", got, err)
	}
	res, err := s.GetProfile(context.Background(), api.GetProfileRequestObject{Username: *meOfCtx(as(ann), t, s).Username})
	if err != nil {
		t.Fatal(err)
	}
	if a := res.(api.GetProfile200JSONResponse).Activity; a.Learned == nil || *a.Learned != 12 {
		t.Fatal("profiles show cards learned")
	}
}
