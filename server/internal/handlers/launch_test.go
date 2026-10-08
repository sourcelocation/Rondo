package handlers

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"

	openapi_types "github.com/oapi-codegen/runtime/types"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/launch"
	"github.com/sourcelocation/rondo/server/internal/problem"
)

// fakeListmonk stands in for Listmonk's subscribers API: it keeps the addresses it's given and
// answers a known one with 409, as Listmonk does.
type fakeListmonk struct {
	mu     sync.Mutex
	posts  []map[string]any
	known  map[string]bool
	status int // answered instead, when set
}

func (f *fakeListmonk) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || r.URL.Path != "/api/subscribers" || r.Header.Get("Authorization") != "token rondo:secret" {
		w.WriteHeader(http.StatusForbidden)
		return
	}
	var body map[string]any
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		w.WriteHeader(http.StatusBadRequest)
		return
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	f.posts = append(f.posts, body)
	email, _ := body["email"].(string)
	switch {
	case f.status != 0:
		w.WriteHeader(f.status)
	case f.known[email]:
		w.WriteHeader(http.StatusConflict)
	default:
		f.known[email] = true
		_, _ = w.Write([]byte(`{"data": {}}`))
	}
}

func launchServer(t *testing.T) (*Server, *fakeListmonk) {
	t.Helper()
	fake := &fakeListmonk{known: map[string]bool{}}
	backend := httptest.NewServer(fake)
	t.Cleanup(backend.Close)
	return &Server{Launch: &launch.List{URL: backend.URL, Token: "rondo:secret", ID: 7, HTTP: http.DefaultClient}}, fake
}

func join(ctx context.Context, s *Server, email string) error {
	_, err := s.JoinLaunchList(ctx, api.JoinLaunchListRequestObject{Body: &api.LaunchListJoin{Email: openapi_types.Email(email)}})
	return err
}

func refusedWith(err error, status int) bool {
	var p *problem.Problem
	return errors.As(err, &p) && p.Status == status
}

func TestLaunchListHandsAddressesToListmonk(t *testing.T) {
	s, fake := launchServer(t)
	if err := join(from(context.Background(), "192.0.2.1"), s, "  Someone@Example.com "); err != nil {
		t.Fatal(err)
	}
	if len(fake.posts) != 1 {
		t.Fatalf("listmonk got %d requests, want 1", len(fake.posts))
	}
	post := fake.posts[0]
	if post["email"] != "someone@example.com" || post["status"] != "enabled" {
		t.Fatalf("listmonk got %v", post)
	}
	if lists, _ := post["lists"].([]any); len(lists) != 1 || lists[0] != float64(7) {
		t.Fatalf("listmonk got lists %v, want [7]", post["lists"])
	}
	// Unconfirmed, so Listmonk sends the link to confirm; it never adds anyone confirmed.
	if _, ok := post["preconfirm_subscriptions"]; ok {
		t.Fatalf("listmonk was told to skip the confirmation: %v", post)
	}
	if _, ok := post["name"]; ok {
		t.Fatalf("listmonk got a name: %v", post)
	}
}

func TestLaunchListAddressKnownAlready(t *testing.T) {
	s, fake := launchServer(t)
	if err := join(from(context.Background(), "192.0.2.2"), s, "twice@example.com"); err != nil {
		t.Fatal(err)
	}
	// Listmonk answers 409 and sends nothing; the visitor is told the same as the first time.
	if err := join(from(context.Background(), "192.0.2.2"), s, "twice@example.com"); err != nil {
		t.Fatalf("a known address: %v", err)
	}
	if len(fake.posts) != 2 {
		t.Fatalf("listmonk got %d requests, want 2", len(fake.posts))
	}
}

func TestLaunchListThreeAnHourFromOneNetwork(t *testing.T) {
	s, fake := launchServer(t)
	for i, email := range []string{"a@example.com", "b@example.com", "c@example.com"} {
		if err := join(from(context.Background(), "192.0.2.3"), s, email); err != nil {
			t.Fatalf("sign-up %d: %v", i+1, err)
		}
	}
	if err := join(from(context.Background(), "192.0.2.3"), s, "d@example.com"); !refusedWith(err, http.StatusTooManyRequests) {
		t.Fatalf("a fourth sign-up: %v, want 429", err)
	}
	if len(fake.posts) != 3 {
		t.Fatalf("listmonk got %d requests, want 3", len(fake.posts))
	}
	// Another address is another network.
	if err := join(from(context.Background(), "192.0.2.4"), s, "d@example.com"); err != nil {
		t.Fatal(err)
	}
}

func TestLaunchListCountsAnIPv6NetworkAsOne(t *testing.T) {
	s, _ := launchServer(t)
	for _, ip := range []string{"2001:db8:1:1::1", "2001:db8:1:1::2", "2001:db8:1:1:ffff::3"} {
		if err := join(from(context.Background(), ip), s, "v6@example.com"); err != nil {
			t.Fatal(err)
		}
	}
	if err := join(from(context.Background(), "2001:db8:1:1:abcd::9"), s, "v6@example.com"); !refusedWith(err, http.StatusTooManyRequests) {
		t.Fatalf("a fourth sign-up from the same /64: %v, want 429", err)
	}
	if err := join(from(context.Background(), "2001:db8:1:2::1"), s, "v6@example.com"); err != nil {
		t.Fatalf("another /64: %v", err)
	}
}

func TestLaunchListRefusals(t *testing.T) {
	s, fake := launchServer(t)
	fake.status = http.StatusBadRequest
	if err := join(from(context.Background(), "192.0.2.5"), s, "x@blocked.example"); !refusedWith(err, http.StatusBadRequest) {
		t.Fatalf("an address Listmonk won't take: %v, want 400", err)
	}
	fake.status = http.StatusInternalServerError
	err := join(from(context.Background(), "192.0.2.5"), s, "y@example.com")
	var p *problem.Problem
	if err == nil || errors.As(err, &p) {
		t.Fatalf("Listmonk failing: %v, want an unexpected error", err)
	}
	closed := &Server{Launch: &launch.List{}}
	if err := join(from(context.Background(), "192.0.2.6"), closed, "z@example.com"); !refusedWith(err, http.StatusServiceUnavailable) {
		t.Fatalf("no Listmonk set up: %v, want 503", err)
	}
}
