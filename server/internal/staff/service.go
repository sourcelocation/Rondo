package staff

import (
	"context"
	"log/slog"
	"strings"
	"sync/atomic"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/riverqueue/river"
	"github.com/sourcelocation/dawl/subscription"

	"github.com/sourcelocation/rondo/server/internal/notify"
	"github.com/sourcelocation/rondo/server/internal/ory"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// Service resolves access and runs staff actions.
type Service struct {
	Pool   *pgxpool.Pool
	Ory    *ory.Client
	Notify *notify.Sender
	Jobs   *river.Client[pgx.Tx]
	Log    *slog.Logger
	// Renewers turn a banned person's renewals off, for the stores that allow it from the server.
	Renewers map[subscription.Provider]subscription.Renewer
	// OwnerEmail is RONDO_OWNER_EMAIL: whoever signs in with it, once it's verified, is the owner.
	OwnerEmail string
	// SkipPasskey lets local development use staff tools without a passkey (never in production:
	// the configuration only allows it on localhost).
	SkipPasskey bool
	owner       atomic.Pointer[uuid.UUID]
}

func (s *Service) q() *store.Queries { return store.New(s.Pool) }

// ResolveOwner finds the owner's identity by RONDO_OWNER_EMAIL; the address must be verified, so
// changing an email to it isn't enough. Run at launch and when someone registers.
func (s *Service) ResolveOwner(ctx context.Context) error {
	if s.OwnerEmail == "" || s.owner.Load() != nil {
		return nil
	}
	who, err := s.Ory.FindByEmail(ctx, s.OwnerEmail)
	if err != nil || who == nil || !who.Verified {
		return err
	}
	if err := store.Ensure(ctx, s.q(), who.ID); err != nil {
		return err
	}
	s.owner.Store(&who.ID)
	return nil
}

// OwnerID is the owner, when known.
func (s *Service) OwnerID(ctx context.Context) (uuid.UUID, bool) {
	if s.owner.Load() == nil {
		if err := s.ResolveOwner(ctx); err != nil && s.Log != nil {
			s.Log.Warn("resolving the owner", "err", err)
		}
	}
	if id := s.owner.Load(); id != nil {
		return *id, true
	}
	return uuid.Nil, false
}

func (s *Service) isOwner(session *ory.Session) bool {
	if s.OwnerEmail == "" || !session.EmailVerified || !strings.EqualFold(session.Email, s.OwnerEmail) {
		return false
	}
	if s.owner.Load() == nil {
		id := session.ID
		s.owner.Store(&id)
	}
	return *s.owner.Load() == session.ID
}

// Access is what a signed-in session may do.
func (s *Service) Access(ctx context.Context, session *ory.Session) (*Access, error) {
	roles, frozen, err := s.roles(ctx, session.ID)
	if err != nil {
		return nil, err
	}
	if s.isOwner(session) {
		roles = append([]Role{Owner}, roles...)
	}
	return newAccess(session.ID, roles, frozen, session, s.SkipPasskey), nil
}

// AccessOf is someone's roles without a session: for ranks, when someone else acts on them.
func (s *Service) AccessOf(ctx context.Context, user uuid.UUID) (*Access, error) {
	roles, frozen, err := s.roles(ctx, user)
	if err != nil {
		return nil, err
	}
	if owner, ok := s.OwnerID(ctx); ok && owner == user {
		roles = append([]Role{Owner}, roles...)
	}
	return newAccess(user, roles, frozen, nil, false), nil
}

func (s *Service) roles(ctx context.Context, user uuid.UUID) ([]Role, bool, error) {
	roles, err := s.q().RolesOf(ctx, user)
	if err != nil || len(roles) == 0 {
		return roles, false, err
	}
	frozen, err := s.q().Frozen(ctx, &user)
	return roles, frozen, err
}

// rankOf is a person's rank now; 0 for nobody (Rondo itself, or someone who left).
func (s *Service) rankOf(ctx context.Context, user *uuid.UUID) (int, error) {
	if user == nil {
		return 0, nil
	}
	a, err := s.AccessOf(ctx, *user)
	if err != nil {
		return 0, err
	}
	return a.Rank(), nil
}

// tx runs [f] in a transaction.
func (s *Service) tx(ctx context.Context, f func(pgx.Tx) error) error {
	return pgx.BeginFunc(ctx, s.Pool, f)
}
