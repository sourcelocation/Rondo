// Package pro decides until when someone has Pro: from every subscription they have, store-bought,
// granted by staff or redeemed from a code.
package pro

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/sourcelocation/dawl/subscription"

	"github.com/sourcelocation/rondo/server/internal/store"
)

// Providers that aren't stores: Pro given by staff and Pro from a code. Their subscriptions are
// active with renewal off, so they end when the grant or code does, and revoked when taken back.
const (
	Grant = "grant"
	Promo = "promo"
)

func Active(until *time.Time) bool { return until != nil && until.After(time.Now()) }

// Recompute sets users.pro_until from all of a person's subscriptions, by dawl's rule (the latest
// State.Until), and says whether Pro started: the person didn't have it before and does now.
func Recompute(ctx context.Context, q *store.Queries, user uuid.UUID) (started bool, err error) {
	u, err := q.User(ctx, user)
	if err != nil {
		return false, err
	}
	subs, err := q.Subscriptions(ctx, user)
	if err != nil {
		return false, err
	}
	var until *time.Time
	for _, sub := range subs {
		state := subscription.State{Status: subscription.Status(sub.Status), CurrentPeriodEnd: sub.PeriodEnd, AutoRenew: sub.AutoRenew}
		if end, ok := state.Until(); ok && (until == nil || end.After(*until)) {
			until = &end
		}
	}
	if err := q.SetProUntil(ctx, store.SetProUntilParams{ID: user, ProUntil: until}); err != nil {
		return false, err
	}
	return !Active(u.ProUntil) && Active(until), nil
}
