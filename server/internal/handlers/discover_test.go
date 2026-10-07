package handlers

import (
	"context"
	"strings"
	"testing"

	"github.com/google/uuid"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/store"
)

func listed(t *testing.T, s *Server, params api.SearchDiscoverParams) []string {
	t.Helper()
	res, err := s.SearchDiscover(context.Background(), api.SearchDiscoverRequestObject{Params: params})
	if err != nil {
		t.Fatal(err)
	}
	var out []string
	for _, i := range res.(api.SearchDiscover200JSONResponse).Items {
		out = append(out, i.Name)
	}
	return out
}

func TestDiscoverSorts(t *testing.T) {
	s := server(t)
	ctx := context.Background()
	alice := person(t, s, "Alice", false)
	publish := func(name, language, age string) (uuid.UUID, string) {
		d := deck(t, s, alice, name)
		if _, err := s.Pool.Exec(ctx, "UPDATE decks SET language = $2 WHERE id = $1", d, language); err != nil {
			t.Fatal(err)
		}
		res, err := s.PublishDeck(as(alice), api.PublishDeckRequestObject{DeckId: d})
		if err != nil {
			t.Fatal(err)
		}
		if _, err := s.Pool.Exec(ctx, "UPDATE publications SET published_at = now() - $2::interval WHERE deck_id = $1", d, age); err != nil {
			t.Fatal(err)
		}
		return d, res.(api.PublishDeck200JSONResponse).Slug
	}
	_, oldSlug := publish("Classic", "ja", "300 days")
	middle, _ := publish("Steady", "ja", "100 days")
	_, newSlug := publish("Fresh", "es", "1 hour")
	for range 5 {
		if err := s.Q.AddShare(ctx, store.AddShareParams{DeckID: middle, UserID: person(t, s, "", false), Role: 1}); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := s.Pool.Exec(ctx, "UPDATE shares SET created_at = now() - interval '30 days' WHERE deck_id = $1", middle); err != nil {
		t.Fatal(err)
	}
	for range 3 {
		if _, err := s.FollowDeck(as(person(t, s, "", false)), api.FollowDeckRequestObject{Slug: oldSlug}); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := s.FollowDeck(as(person(t, s, "", false)), api.FollowDeckRequestObject{Slug: newSlug}); err != nil {
		t.Fatal(err)
	}
	if err := s.Q.RefreshPublications(ctx, nil); err != nil {
		t.Fatal(err)
	}
	sort := func(o api.SearchDiscoverParamsSort) *api.SearchDiscoverParamsSort { return &o }
	for _, c := range []struct {
		params api.SearchDiscoverParams
		want   string
	}{
		{api.SearchDiscoverParams{}, "Steady Classic Fresh"},
		{api.SearchDiscoverParams{Sort: sort(api.New)}, "Fresh Steady Classic"},
		{api.SearchDiscoverParams{Sort: sort(api.Hot)}, "Classic Fresh Steady"},
		{api.SearchDiscoverParams{Language: ptr("ja"), Sort: sort(api.New)}, "Steady Classic"},
		{api.SearchDiscoverParams{Q: ptr("res")}, "Fresh"},
	} {
		if got := listed(t, s, c.params); strings.Join(got, " ") != c.want {
			t.Errorf("%+v: %v, want %s", c.params, got, c.want)
		}
	}
}
