package staff

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/sourcelocation/rondo/server/internal/notify"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// Area is something the log decides, derived again after every change to it: from the entries not
// undone, so doing, undoing and expiry all end in the same place.
type Area struct {
	Name   string
	derive func(ctx context.Context, s *Service, tx pgx.Tx, e *Entry) error
}

// Roles: the roles table, from role.grant and role.revoke entries.
var Roles = Area{"roles", func(ctx context.Context, _ *Service, tx pgx.Tx, e *Entry) error {
	q := store.New(tx)
	if err := q.ClearRoles(ctx, *e.UserID); err != nil {
		return err
	}
	return q.DeriveRoles(ctx, *e.UserID)
}}

// limit counts what an actor did in the last hour against the kind's limit. Going over freezes the
// actor (their staff powers stop until someone undoes the freeze) and tells the owner.
func (s *Service) limit(ctx context.Context, actor *Access, k *Kind) error {
	if actor.Owner() || k.Limit == 0 {
		return nil
	}
	q := store.New(s.Pool)
	since := time.Now().Add(-time.Hour)
	var n int64
	var err error
	if k == undoKind {
		n, err = q.CountRecentUndos(ctx, store.CountRecentUndosParams{UndoneBy: &actor.User, UndoneAt: &since})
	} else {
		n, err = q.CountRecentActions(ctx, store.CountRecentActionsParams{ActorID: &actor.User, Kind: k.Name, CreatedAt: since})
	}
	if err != nil || n < int64(k.Limit) {
		return err
	}
	if err := s.freeze(ctx, actor.User, k.Name); err != nil {
		return err
	}
	return errFrozen
}

func (s *Service) freeze(ctx context.Context, user uuid.UUID, over string) error {
	who, err := s.Label(ctx, user)
	if err != nil {
		return err
	}
	owner, known := s.OwnerID(ctx)
	e := &Entry{StaffAction: store.StaffAction{ID: uuid.Must(uuid.NewV7()), Kind: freezeKind.Name, UserID: &user}, Data: map[string]string{"over": over}}
	return s.tx(ctx, func(tx pgx.Tx) error {
		if err := s.insert(ctx, tx, e); err != nil {
			return err
		}
		if !known {
			return nil
		}
		return s.Notify.Send(ctx, tx, owner, notify.Frozen(who, over))
	})
}

// Label is how staff and notifications name someone: @username, else their name.
func (s *Service) Label(ctx context.Context, user uuid.UUID) (string, error) {
	refs, err := store.New(s.Pool).UserRefs(ctx, []uuid.UUID{user})
	if err != nil {
		return "", err
	}
	for _, r := range refs {
		if r.Name != nil {
			return *r.Name, nil
		}
	}
	return "Someone", nil
}
