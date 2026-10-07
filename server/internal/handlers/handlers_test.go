package handlers

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	openapi_types "github.com/oapi-codegen/runtime/types"
	"github.com/riverqueue/river"
	"github.com/riverqueue/river/riverdriver/riverpgxv5"
	"github.com/sourcelocation/dawl/subscription"

	"github.com/sourcelocation/rondo/server/db"
	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/blob"
	"github.com/sourcelocation/rondo/server/internal/notify"
	"github.com/sourcelocation/rondo/server/internal/ory"
	"github.com/sourcelocation/rondo/server/internal/staff"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// fakeOry stands in for Kratos's and Hydra's admin APIs: it records every request and answers
// what tests set in [replies] (by "METHOD /path"), or an empty success.
type fakeOry struct {
	mu      sync.Mutex
	calls   []string
	replies map[string]string
}

func (f *fakeOry) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	key := r.Method + " " + r.URL.Path
	f.calls = append(f.calls, key+"?"+r.URL.RawQuery)
	reply, ok := f.replies[key]
	f.mu.Unlock()
	switch {
	case ok:
		_, _ = w.Write([]byte(reply))
	case r.Method == http.MethodGet && r.URL.Path == "/admin/identities":
		_, _ = w.Write([]byte("[]"))
	case r.Method == http.MethodGet:
		w.WriteHeader(http.StatusNotFound)
	default:
		w.WriteHeader(http.StatusNoContent)
	}
}

// called says whether a request starting with [prefix] ("METHOD /path?query") was made.
func (f *fakeOry) called(prefix string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, c := range f.calls {
		if strings.HasPrefix(c, prefix) {
			return true
		}
	}
	return false
}

const ownerEmail = "owner@example.com"

// server returns a Server on a fresh schema in RONDO_TEST_GO_DATABASE_URL (the compose database
// rondo_test_go by default), with Kratos and Hydra faked by [fakeOry].
func server(t *testing.T) *Server {
	s, _ := serverWithOry(t)
	return s
}

func serverWithOry(t *testing.T) (*Server, *fakeOry) {
	t.Helper()
	url := os.Getenv("RONDO_TEST_GO_DATABASE_URL")
	if url == "" {
		url = "postgres://rondo:rondo@localhost:23903/rondo_test_go?sslmode=disable"
	}
	ctx := context.Background()
	pool, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	if _, err := pool.Exec(ctx, "DROP SCHEMA public CASCADE; CREATE SCHEMA public"); err != nil {
		t.Fatal(err)
	}
	if err := db.Migrate(ctx, url); err != nil {
		t.Fatal(err)
	}
	queue, err := river.NewClient(riverpgxv5.New(pool), &river.Config{})
	if err != nil {
		t.Fatal(err)
	}
	fake := &fakeOry{replies: map[string]string{}}
	backend := httptest.NewServer(fake)
	t.Cleanup(backend.Close)
	client := &ory.Client{KratosPublic: backend.URL, KratosAdmin: backend.URL, HydraAdmin: backend.URL, HTTP: http.DefaultClient}
	sender := &notify.Sender{Jobs: queue}
	return &Server{
		Q: store.New(pool), Pool: pool, Jobs: queue, Blobs: blob.Folder{Dir: t.TempDir(), Base: "http://app"}, Ory: client, Notify: sender,
		Staff: &staff.Service{Pool: pool, Ory: client, Notify: sender, Jobs: queue, OwnerEmail: ownerEmail},
		S:     Settings{PublicURL: "http://app", FreeMediaBytes: 1 << 20, ProMediaBytes: 1 << 30},
	}, fake
}

func as(id uuid.UUID) context.Context {
	return context.WithValue(context.Background(), identityKey{}, &ory.Session{ID: id, Email: id.String() + "@example.com", AuthenticatedAt: time.Now()})
}

// asStaff is someone signed in with a passkey just now; the owner when [email] is [ownerEmail].
func asStaff(id uuid.UUID, email string) context.Context {
	now := time.Now()
	return context.WithValue(context.Background(), identityKey{}, &ory.Session{
		ID: id, Email: email, EmailVerified: true, AuthenticatedAt: now, Methods: []ory.Method{{Method: "passkey", CompletedAt: now}},
	})
}

func person(t *testing.T, s *Server, name string, proUser bool) uuid.UUID {
	id := uuid.Must(uuid.NewV7())
	// People with a name have confirmed their profile.
	if _, err := s.Pool.Exec(context.Background(), `INSERT INTO users (id, name, username, profile_confirmed_at, pro_until)
		VALUES ($1, NULLIF($2, ''), 'user' || floor(random() * 1e9)::bigint, CASE WHEN $2 <> '' THEN now() END,
		CASE WHEN $3 THEN now() + interval '1 day' END)`, id, name, proUser); err != nil {
		t.Fatal(err)
	}
	return id
}

func deck(t *testing.T, s *Server, owner uuid.UUID, name string) uuid.UUID {
	id := uuid.Must(uuid.NewV7())
	if _, err := s.Pool.Exec(context.Background(), "INSERT INTO decks (id, owner_id, name, position, v, seq) VALUES ($1, $2, $3, 'V', 1, 1)", id, owner, name); err != nil {
		t.Fatal(err)
	}
	return id
}

func code(err error) api.ErrorCode {
	var p *Problem
	if errors.As(err, &p) {
		return p.Code
	}
	return ""
}

func TestInvitationsLendDecks(t *testing.T) {
	s := server(t)
	alice, bob, anon := person(t, s, "Alice", false), person(t, s, "Bob", false), person(t, s, "", false)
	d := deck(t, s, alice, "Spanish")
	if _, err := s.CreateInvite(as(anon), api.CreateInviteRequestObject{DeckId: d, Body: &api.InviteRequest{Role: 1}}); code(err) != api.Forbidden {
		t.Fatalf("someone else's deck: %v", err)
	}
	if _, err := s.CreateInvite(as(alice), api.CreateInviteRequestObject{DeckId: d, Body: &api.InviteRequest{Role: 2}}); code(err) != api.ProRequired {
		t.Fatalf("editors need Pro: %v", err)
	}
	res, err := s.CreateInvite(as(alice), api.CreateInviteRequestObject{DeckId: d, Body: &api.InviteRequest{Role: 1}})
	if err != nil {
		t.Fatal(err)
	}
	token := strings.TrimPrefix(*res.(api.CreateInvite201JSONResponse).Url, "http://app/i/")
	preview, err := s.PreviewInvitation(context.Background(), api.PreviewInvitationRequestObject{Token: token})
	if err != nil || preview.(api.PreviewInvitation200JSONResponse).Deck.Item.Name != "Spanish" {
		t.Fatalf("preview: %v %v", preview, err)
	}
	if _, err := s.AcceptInvitation(as(bob), api.AcceptInvitationRequestObject{Token: token}); err != nil {
		t.Fatal(err)
	}
	me, err := s.GetMe(as(alice), api.GetMeRequestObject{})
	if err != nil || len(*me.(api.GetMe200JSONResponse).Lending) != 1 {
		t.Fatalf("lending: %v %v", me, err)
	}
	shares, _ := s.Q.Shares(context.Background(), d)
	if len(shares) != 1 || shares[0].UserID != bob || shares[0].Role != 1 {
		t.Fatalf("shares: %+v", shares)
	}
	if _, err := s.RemoveShare(as(bob), api.RemoveShareRequestObject{DeckId: d, UserId: bob}); err != nil {
		t.Fatalf("leaving: %v", err)
	}
	email := "carol@example.com"
	if _, err := s.CreateInvite(as(alice), api.CreateInviteRequestObject{DeckId: d, Body: &api.InviteRequest{Role: 1, Email: (*openapi_types.Email)(&email)}}); err != nil {
		t.Fatalf("email invite: %v", err)
	}
	var jobs int
	_ = s.Pool.QueryRow(context.Background(), "SELECT count(*) FROM river_job WHERE kind = 'mail'").Scan(&jobs)
	if jobs != 1 {
		t.Fatalf("invitation mail queued: %d", jobs)
	}
}

func TestPublishingFollowingAndUnpublishing(t *testing.T) {
	s := server(t)
	alice, bob := person(t, s, "Alice", false), person(t, s, "Bob", false)
	d := deck(t, s, alice, "HSK 1")
	if _, err := s.PublishDeck(as(alice), api.PublishDeckRequestObject{DeckId: d}); err != nil {
		t.Fatal(err)
	}
	q := "hsk"
	found, err := s.SearchDiscover(context.Background(), api.SearchDiscoverRequestObject{Params: api.SearchDiscoverParams{Q: &q}})
	items := found.(api.SearchDiscover200JSONResponse).Items
	if err != nil || len(items) != 1 {
		t.Fatalf("discover: %v %v", items, err)
	}
	if _, err := s.FollowDeck(as(bob), api.FollowDeckRequestObject{Slug: *items[0].Slug}); err != nil {
		t.Fatal(err)
	}
	page, err := s.GetPublicDeck(context.Background(), api.GetPublicDeckRequestObject{Slug: *items[0].Slug})
	if err != nil || page.(api.GetPublicDeck200JSONResponse).Item.Followers != 1 {
		t.Fatalf("followers: %v %v", page, err)
	}
	if _, err := s.UnpublishDeck(as(alice), api.UnpublishDeckRequestObject{DeckId: d}); err != nil {
		t.Fatal(err)
	}
	found, _ = s.SearchDiscover(context.Background(), api.SearchDiscoverRequestObject{})
	if len(found.(api.SearchDiscover200JSONResponse).Items) != 0 {
		t.Fatal("unlisted decks leave Discover")
	}
	if shares, _ := s.Q.Shares(context.Background(), d); len(shares) != 1 {
		t.Fatal("followers keep the deck")
	}
}

func TestDeletingAnAccountLeavesLentDecksWithoutAnAuthor(t *testing.T) {
	s, fake := serverWithOry(t)
	alice, bob := person(t, s, "Alice", false), person(t, s, "Bob", false)
	d := deck(t, s, alice, "Course")
	ctx := context.Background()
	_ = s.Q.AddShare(ctx, store.AddShareParams{DeckID: d, UserID: bob, Role: 1})
	if _, err := s.Pool.Exec(ctx, "INSERT INTO events (id, user_id, subject_id, kind, at, seq) VALUES ($1, $2, $1, 1, 1, 1)", uuid.Must(uuid.NewV7()), alice); err != nil {
		t.Fatal(err)
	}
	stale := as(alice)
	stale.Value(identityKey{}).(*ory.Session).AuthenticatedAt = time.Now().Add(-time.Hour)
	if _, err := s.DeleteMe(stale, api.DeleteMeRequestObject{}); code(err) != api.Reauthenticate {
		t.Fatalf("needs a recent sign-in: %v", err)
	}
	if _, err := s.DeleteMe(as(alice), api.DeleteMeRequestObject{}); err != nil {
		t.Fatal(err)
	}
	if !fake.called("DELETE /admin/identities/" + alice.String()) {
		t.Fatal("the identity goes from Kratos")
	}
	var owner *uuid.UUID
	var events int
	if err := s.Pool.QueryRow(ctx, "SELECT owner_id FROM decks WHERE id = $1", d).Scan(&owner); err != nil || owner != nil {
		t.Fatalf("deck owner: %v %v", owner, err)
	}
	_ = s.Pool.QueryRow(ctx, "SELECT count(*) FROM events").Scan(&events)
	if events != 0 {
		t.Fatal("the learner's events go with the account")
	}
}

func TestSubscriptionsDecideUntilWhenSomeoneHasPro(t *testing.T) {
	s := server(t)
	alice := person(t, s, "Alice", false)
	end := time.Now().Add(30 * 24 * time.Hour).Truncate(time.Second)
	cases := []struct {
		status    subscription.Status
		autoRenew bool
		want      *time.Time
	}{
		{subscription.StatusActive, true, ptr(end.Add(subscription.Grace))},
		{subscription.StatusActive, false, &end},
		{subscription.StatusCanceled, false, &end},
		{subscription.StatusExpired, false, nil},
	}
	for _, c := range cases {
		state := subscription.State{
			Provider: subscription.Stripe, ProviderRef: "sub_1", ProductID: "pro", Status: c.status, CurrentPeriodEnd: &end,
			AutoRenew: c.autoRenew, Account: alice.String(),
		}
		if err := s.Save(context.Background(), state); err != nil {
			t.Fatal(err)
		}
		u, _ := s.Q.User(context.Background(), alice)
		if (u.ProUntil == nil) != (c.want == nil) || (u.ProUntil != nil && !u.ProUntil.Equal(*c.want)) {
			t.Errorf("%s: pro until %v, want %v", c.status, u.ProUntil, c.want)
		}
	}
}

func TestMediaUploadsAreCheckedAgainstTheirHash(t *testing.T) {
	s := server(t)
	alice := person(t, s, "Alice", false)
	content := "hello"
	hash := "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
	res, err := s.RequestUpload(as(alice), api.RequestUploadRequestObject{Body: &api.UploadRequest{Hash: hash, Size: len(content), Mime: "image/png"}})
	if err != nil || res.(api.RequestUpload200JSONResponse).Exists {
		t.Fatalf("upload target: %v %v", res, err)
	}
	if _, err := s.PutMedia(context.Background(), api.PutMediaRequestObject{Hash: hash, Body: strings.NewReader("forged")}); code(err) != api.Invalid {
		t.Fatalf("a forged file is refused: %v", err)
	}
	if _, err := s.PutMedia(context.Background(), api.PutMediaRequestObject{Hash: hash, Body: strings.NewReader(content)}); err != nil {
		t.Fatal(err)
	}
	again, _ := s.RequestUpload(as(alice), api.RequestUploadRequestObject{Body: &api.UploadRequest{Hash: hash, Size: len(content), Mime: "image/png"}})
	if !again.(api.RequestUpload200JSONResponse).Exists {
		t.Fatal("stored files aren't uploaded twice")
	}
	big := api.UploadRequest{Hash: strings.Repeat("a", 64), Size: 2 << 20, Mime: "image/png"}
	if _, err := s.RequestUpload(as(alice), api.RequestUploadRequestObject{Body: &big}); code(err) != api.QuotaExceeded {
		t.Fatalf("quota: %v", err)
	}
}

func ptr[T any](v T) *T { return &v }
