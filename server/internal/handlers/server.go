// Package handlers implements the Go half of the API (api/rondo.yaml, tag `api`).
package handlers

import (
	"context"
	"crypto/subtle"
	"errors"
	"net/http"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/riverqueue/river"
	"github.com/sourcelocation/dawl/appstore"
	"github.com/sourcelocation/dawl/googleplay"
	"github.com/sourcelocation/dawl/stripe"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/blob"
	"github.com/sourcelocation/rondo/server/internal/idtoken"
	"github.com/sourcelocation/rondo/server/internal/jobs"
	"github.com/sourcelocation/rondo/server/internal/launch"
	"github.com/sourcelocation/rondo/server/internal/mail"
	"github.com/sourcelocation/rondo/server/internal/notify"
	"github.com/sourcelocation/rondo/server/internal/ory"
	"github.com/sourcelocation/rondo/server/internal/problem"
	"github.com/sourcelocation/rondo/server/internal/staff"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// Problem is a refusal the API answers with: a status and an error code from the spec.
type Problem = problem.Problem

func fail(status int, code api.ErrorCode, message string) error {
	return problem.New(status, code, message)
}

var (
	errSignIn   = problem.SignIn
	errNotFound = problem.NotFound
	errNotYours = fail(http.StatusForbidden, api.Forbidden, "Only the deck's owner can do that.")
)

// Settings are what handlers need from the configuration.
type Settings struct {
	PublicURL, HookSecret, EmailKeySecret string
	FreeMediaBytes, ProMediaBytes         int64
	StripePrices                          map[string]string
}

type Server struct {
	Q        *store.Queries
	Pool     *pgxpool.Pool
	Blobs    blob.Store
	Jobs     *river.Client[pgx.Tx]
	Ory      *ory.Client
	Staff    *staff.Service
	Notify   *notify.Sender
	S        Settings
	Stripe   *stripe.Gateway
	AppStore *appstore.Gateway
	Play     *googleplay.Gateway
	// Tokens checks Google's and Apple's ID tokens, for linking those sign-ins.
	Tokens idtoken.Verifier
	// Pusher sends Web Push, when it's set up.
	Pusher *notify.Pusher
	// Launch is the launch list in Listmonk, open when it's set up.
	Launch *launch.List

	mu       sync.Mutex
	sessions map[string]session
}

type session struct {
	who      *ory.Session
	expires  time.Time
	stepping bool // signed in, but a second factor is still due
}

type identityKey struct{}

type secondFactorKey struct{}

type tokenKey struct{}

// Identify resolves the bearer token with Kratos (remembered for a minute) for handlers to read. The
// account itself (/api/me) always asks again, so a sign-in with a passkey or a second factor shows
// at once.
func (s *Server) Identify(r *http.Request) *http.Request {
	token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !ok || token == "" {
		return r
	}
	s.mu.Lock()
	cached, hit := s.sessions[token]
	s.mu.Unlock()
	if !hit || time.Now().After(cached.expires) || r.URL.Path == "/api/me" {
		who, err := s.Ory.Whoami(r.Context(), token)
		if err != nil && !errors.Is(err, ory.ErrSecondFactor) {
			return r // Kratos is unreachable: not signed in, this time
		}
		cached = session{who: who, expires: time.Now().Add(time.Minute), stepping: errors.Is(err, ory.ErrSecondFactor)}
		s.mu.Lock()
		if s.sessions == nil || len(s.sessions) > 50_000 {
			s.sessions = map[string]session{}
		}
		s.sessions[token] = cached
		s.mu.Unlock()
	}
	ctx := context.WithValue(r.Context(), tokenKey{}, token)
	if cached.stepping {
		ctx = context.WithValue(ctx, secondFactorKey{}, true)
	}
	if cached.who != nil {
		ctx = context.WithValue(ctx, identityKey{}, cached.who)
	}
	return r.WithContext(ctx)
}

func me(ctx context.Context) (*ory.Session, error) {
	if id, ok := ctx.Value(identityKey{}).(*ory.Session); ok {
		return id, nil
	}
	if stepping, _ := ctx.Value(secondFactorKey{}).(bool); stepping {
		return nil, problem.SecondFactor
	}
	return nil, errSignIn
}

// owned is deck, when the signed-in person owns it.
func (s *Server) owned(ctx context.Context, deck uuid.UUID) (*ory.Session, store.DeckRow, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, store.DeckRow{}, err
	}
	d, err := s.Q.Deck(ctx, deck)
	if errors.Is(err, pgx.ErrNoRows) || (err == nil && d.DeletedAt != nil) {
		return nil, d, errNotFound
	}
	if err != nil {
		return nil, d, err
	}
	if d.OwnerID == nil || *d.OwnerID != id.ID {
		return nil, d, errNotYours
	}
	return id, d, nil
}

func (s *Server) user(ctx context.Context, id uuid.UUID) (store.User, error) {
	if err := store.Ensure(ctx, s.Q, id); err != nil {
		return store.User{}, err
	}
	return s.Q.User(ctx, id)
}

func (s *Server) hook(ctx context.Context) error {
	got, _ := ctx.Value(hookKey{}).(string)
	if s.S.HookSecret == "" || subtle.ConstantTimeCompare([]byte(got), []byte(s.S.HookSecret)) != 1 {
		return fail(http.StatusUnauthorized, api.Unauthorized, "Not Kratos.")
	}
	return nil
}

type hookKey struct{}

// WithHookSecret keeps the Authorization header of hook requests for [Server.hook].
func WithHookSecret(r *http.Request) *http.Request {
	return r.WithContext(context.WithValue(r.Context(), hookKey{}, r.Header.Get("Authorization")))
}

func (s *Server) enqueue(ctx context.Context, args river.JobArgs) error {
	_, err := s.Jobs.Insert(ctx, args, nil)
	return err
}

// tx runs [f] in a transaction.
func (s *Server) tx(ctx context.Context, f func(pgx.Tx) error) error {
	return pgx.BeginFunc(ctx, s.Pool, f)
}

// Account -------------------------------------------------------------------------------------

func (s *Server) GetMe(ctx context.Context, _ api.GetMeRequestObject) (api.GetMeResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	u, err := s.user(ctx, id.ID)
	if err != nil {
		return nil, err
	}
	if err := s.seen(ctx, id.ID, id.Email); err != nil {
		return nil, err
	}
	m := meOf(u)
	// Signing in with the second step shows it isn't lost: any removal someone asked for stops.
	if slices.ContainsFunc(id.Methods, func(m ory.Method) bool { return m.Method == "totp" || m.Method == "lookup_secret" }) {
		if err := s.Q.EndReset(ctx, id.ID); err != nil {
			return nil, err
		}
	} else if reset, err := s.Q.Reset(ctx, id.ID); err == nil {
		m.SecondFactorResetAt = reset.DueAt
	}
	if c := client(ctx).Country; c != "" {
		m.SuggestedFlag = &c
	}
	if active(u.RestrictedUntil) {
		m.RestrictedUntil = u.RestrictedUntil
	}
	lending, err := s.Q.Lending(ctx, &u.ID)
	if err != nil {
		return nil, err
	}
	m.Lending = &lending
	access, err := s.Staff.Access(ctx, id)
	if err != nil {
		return nil, err
	}
	roles, permissions, locked := access.Roles, access.Permissions(), access.Locked()
	m.Roles, m.Permissions, m.StaffLocked = &roles, &permissions, &locked
	unseen, err := s.Q.UnseenNotifications(ctx, u.ID)
	if err != nil {
		return nil, err
	}
	notifications := make([]api.Notification, 0, len(unseen))
	for _, n := range unseen {
		notifications = append(notifications, api.Notification{
			Id: n.ID, Kind: n.Kind, Presentation: notify.Presentation(n.Presentation).String(), Title: n.Title, Body: n.Body,
			LinkLabel: n.LinkLabel, LinkUrl: n.LinkUrl, CreatedAt: n.CreatedAt,
		})
	}
	m.Notifications = &notifications
	return api.GetMe200JSONResponse(m), nil
}

func (s *Server) MarkSeen(ctx context.Context, r api.MarkSeenRequestObject) (api.MarkSeenResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	if _, err := s.Q.MarkSeen(ctx, store.MarkSeenParams{ID: r.NotificationId, UserID: id.ID}); err != nil {
		return nil, err
	}
	return api.MarkSeen204Response{}, nil
}

// DeleteMe removes the account: Kratos's identity, then the user, which cascades to the person's
// own rows and leaves decks others borrow without an author.
func (s *Server) DeleteMe(ctx context.Context, _ api.DeleteMeRequestObject) (api.DeleteMeResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	if time.Since(id.AuthenticatedAt) > 10*time.Minute {
		return nil, fail(http.StatusForbidden, api.Reauthenticate, "Confirm it's you first.")
	}
	if u, err := s.Q.User(ctx, id.ID); err == nil && u.StripeCustomer != nil && s.Stripe != nil {
		if err := s.Stripe.DeleteCustomer(ctx, *u.StripeCustomer); err != nil {
			return nil, err
		}
	}
	if err := s.Ory.Delete(ctx, id.ID); err != nil {
		return nil, err
	}
	if err := s.Q.DeleteUser(ctx, id.ID); err != nil {
		return nil, err
	}
	return api.DeleteMe204Response{}, nil
}

func (s *Server) KratosRegistration(ctx context.Context, r api.KratosRegistrationRequestObject) (api.KratosRegistrationResponseObject, error) {
	if err := s.hook(ctx); err != nil {
		return nil, err
	}
	if err := store.Ensure(ctx, s.Q, r.Body.IdentityId); err != nil {
		return nil, err
	}
	return api.KratosRegistration204Response{}, s.Staff.ResolveOwner(ctx)
}

func (s *Server) KratosCourier(ctx context.Context, r api.KratosCourierRequestObject) (api.KratosCourierResponseObject, error) {
	if err := s.hook(ctx); err != nil {
		return nil, err
	}
	m := r.Body
	return api.KratosCourier204Response{}, s.enqueue(ctx, jobs.Mail{Message: mail.FromKratos(m.TemplateType, m.Recipient, deref(m.Code), deref(m.Url))})
}

func (s *Server) RequestExport(ctx context.Context, _ api.RequestExportRequestObject) (api.RequestExportResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	return api.RequestExport202Response{}, s.enqueue(ctx, jobs.Export{User: id.ID, Email: id.Email})
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}
