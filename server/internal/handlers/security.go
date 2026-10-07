package handlers

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"errors"
	"math/big"
	"net/http"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/riverqueue/river"
	"golang.org/x/time/rate"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/idtoken"
	"github.com/sourcelocation/rondo/server/internal/jobs"
	"github.com/sourcelocation/rondo/server/internal/mail"
	"github.com/sourcelocation/rondo/server/internal/ory"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// Account security beyond Kratos's own flows (TOTP, recovery codes and signed-in devices are those,
// driven by the apps): changing the email, linking Google and Apple, and removing a lost second step.

const (
	codeLife  = 15 * time.Minute
	codeTries = 5
	// resetWait is how long a lost second step takes to go, so its owner can stop a stranger.
	resetWait = 7 * 24 * time.Hour
)

var (
	errReauth    = fail(http.StatusForbidden, api.Reauthenticate, "Confirm it's you first.")
	errWrongCode = fail(http.StatusBadRequest, api.CodeInvalid, "That code isn't right, or it expired.")
)

// code is six digits to type, and how it's kept: hashed with whom it's for.
func newCode(salt string) (string, []byte) {
	n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		panic(err)
	}
	digits := n.String()
	digits = strings.Repeat("0", 6-len(digits)) + digits
	return digits, hashCode(salt, digits)
}

func hashCode(salt, digits string) []byte {
	sum := sha256.Sum256([]byte(salt + ":" + strings.TrimSpace(digits)))
	return sum[:]
}

func recent(id *ory.Session) bool { return time.Since(id.AuthenticatedAt) <= 10*time.Minute }

func (s *Server) ChangeEmail(ctx context.Context, r api.ChangeEmailRequestObject) (api.ChangeEmailResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	if !recent(id) {
		return nil, errReauth
	}
	email := strings.ToLower(strings.TrimSpace(string(r.Body.Email)))
	if strings.EqualFold(email, id.Email) {
		return nil, fail(http.StatusBadRequest, api.Invalid, "That's your email already.")
	}
	other, err := s.Ory.FindByEmail(ctx, email)
	if err != nil {
		return nil, err
	}
	if other != nil && other.ID != id.ID {
		return nil, fail(http.StatusConflict, api.Conflict, "Another account signs in with that email.")
	}
	digits, hash := newCode(id.ID.String())
	err = s.tx(ctx, func(tx pgx.Tx) error {
		q := store.New(tx)
		if err := q.StartEmailChange(ctx, store.StartEmailChangeParams{UserID: id.ID, Email: email, CodeHash: hash, ExpiresAt: time.Now().Add(codeLife)}); err != nil {
			return err
		}
		_, err := s.Jobs.InsertTx(ctx, tx, jobs.Mail{Message: mail.NewEmailCode(email, digits)}, nil)
		return err
	})
	if err != nil {
		return nil, err
	}
	return api.ChangeEmail202Response{}, nil
}

func (s *Server) ConfirmEmail(ctx context.Context, r api.ConfirmEmailRequestObject) (api.ConfirmEmailResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	change, err := s.Q.EmailChange(ctx, id.ID)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, errWrongCode
	}
	if err != nil {
		return nil, err
	}
	if change.Tries >= codeTries || time.Now().After(change.ExpiresAt) {
		return nil, errWrongCode
	}
	if subtle.ConstantTimeCompare(change.CodeHash, hashCode(id.ID.String(), r.Body.Code)) != 1 {
		if err := s.Q.TryEmailChange(ctx, id.ID); err != nil {
			return nil, err
		}
		return nil, errWrongCode
	}
	if err := s.Ory.SetEmail(ctx, id.ID, change.Email); errors.Is(err, ory.ErrConflict) {
		return nil, fail(http.StatusConflict, api.Conflict, "Another account signs in with that email.")
	} else if err != nil {
		return nil, err
	}
	key := s.emailKey(change.Email)
	err = s.tx(ctx, func(tx pgx.Tx) error {
		q := store.New(tx)
		if err := q.EndEmailChange(ctx, id.ID); err != nil {
			return err
		}
		if err := q.SetEmailKey(ctx, store.SetEmailKeyParams{ID: id.ID, EmailKey: &key}); err != nil {
			return err
		}
		if u, err := q.User(ctx, id.ID); err != nil {
			return err
		} else if u.StripeCustomer != nil && s.Stripe != nil {
			if _, err := s.Jobs.InsertTx(ctx, tx, jobs.CustomerEmail{Customer: *u.StripeCustomer, Email: change.Email}, nil); err != nil {
				return err
			}
		}
		_, err := s.Jobs.InsertTx(ctx, tx, jobs.Mail{Message: mail.EmailChanged(id.Email, change.Email)}, nil)
		return err
	})
	if err != nil {
		return nil, err
	}
	s.forget(ctx)
	u, err := s.user(ctx, id.ID)
	if err != nil {
		return nil, err
	}
	return api.ConfirmEmail200JSONResponse(meOf(u)), nil
}

// forget drops this request's session from the minute-long cache, after a change Kratos has to tell
// the next request about.
func (s *Server) forget(ctx context.Context) {
	token, _ := ctx.Value(tokenKey{}).(string)
	s.mu.Lock()
	delete(s.sessions, token)
	s.mu.Unlock()
}

// Linking Google and Apple ----------------------------------------------------------------------------

func (s *Server) links(ctx context.Context, user uuid.UUID) (api.Links, error) {
	who, err := s.Ory.Identity(ctx, user)
	if err != nil {
		return api.Links{}, err
	}
	out := api.Links{Providers: []string{}}
	if who != nil {
		for _, p := range who.Providers {
			out.Providers = append(out.Providers, p.Provider)
		}
	}
	slices.Sort(out.Providers)
	return out, nil
}

func (s *Server) ListLinks(ctx context.Context, _ api.ListLinksRequestObject) (api.ListLinksResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	links, err := s.links(ctx, id.ID)
	return api.ListLinks200JSONResponse(links), err
}

func (s *Server) Link(ctx context.Context, r api.LinkRequestObject) (api.LinkResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	if s.Tokens == nil {
		return nil, fail(http.StatusServiceUnavailable, api.Unavailable, "Linking isn't set up here.")
	}
	provider := string(r.Body.Provider)
	subject, err := s.Tokens.Subject(ctx, provider, r.Body.IdToken, deref(r.Body.Nonce))
	if errors.Is(err, idtoken.ErrToken) {
		return nil, fail(http.StatusBadRequest, api.Invalid, "That sign-in didn't work. Try again.")
	}
	if errors.Is(err, idtoken.ErrProvider) {
		return nil, fail(http.StatusServiceUnavailable, api.Unavailable, "Linking isn't set up here.")
	}
	if err != nil {
		return nil, err
	}
	who, err := s.Ory.Identity(ctx, id.ID)
	if err != nil || who == nil {
		return nil, errors.Join(err, errNotFound)
	}
	providers := slices.DeleteFunc(slices.Clone(who.Providers), func(p ory.Provider) bool { return p.Provider == provider })
	providers = append(providers, ory.Provider{Provider: provider, Subject: subject})
	if err := s.Ory.SetProviders(ctx, id.ID, providers); errors.Is(err, ory.ErrConflict) {
		return nil, fail(http.StatusConflict, api.Conflict, "That account signs in to another Rondo account.")
	} else if err != nil {
		return nil, err
	}
	links, err := s.links(ctx, id.ID)
	return api.Link200JSONResponse(links), err
}

func (s *Server) Unlink(ctx context.Context, r api.UnlinkRequestObject) (api.UnlinkResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	who, err := s.Ory.Identity(ctx, id.ID)
	if err != nil || who == nil {
		return nil, errors.Join(err, errNotFound)
	}
	for _, p := range who.Providers {
		if p.Provider == string(r.Provider) {
			if err := s.Ory.DeleteCredential(ctx, id.ID, "oidc", p.Provider+":"+p.Subject); err != nil {
				return nil, err
			}
		}
	}
	links, err := s.links(ctx, id.ID)
	return api.Unlink200JSONResponse(links), err
}

// A lost second step ----------------------------------------------------------------------------------

// resetters limits how often a reset is asked for, per address of the asker.
var resetters sync.Map

func resetAllowed(key string) bool {
	l, _ := resetters.LoadOrStore(key, rate.NewLimiter(rate.Every(10*time.Minute), 5))
	return l.(*rate.Limiter).Allow()
}

func (s *Server) RequestReset(ctx context.Context, r api.RequestResetRequestObject) (api.RequestResetResponseObject, error) {
	email := strings.ToLower(strings.TrimSpace(string(r.Body.Email)))
	if !resetAllowed(client(ctx).IP.String()) || !resetAllowed(email) {
		return nil, fail(http.StatusTooManyRequests, api.RateLimited, "That's a lot of tries. Try again in a while.")
	}
	who, err := s.Ory.FindByEmail(ctx, email)
	if err != nil {
		return nil, err
	}
	if who == nil || !slices.ContainsFunc(who.Methods, func(m string) bool { return m == "totp" || m == "lookup_secret" }) {
		return api.RequestReset202Response{}, nil
	}
	if err := store.Ensure(ctx, s.Q, who.ID); err != nil {
		return nil, err
	}
	digits, hash := newCode(who.ID.String())
	err = s.tx(ctx, func(tx pgx.Tx) error {
		q := store.New(tx)
		if err := q.StartReset(ctx, store.StartResetParams{UserID: who.ID, CodeHash: hash, ExpiresAt: time.Now().Add(codeLife)}); err != nil {
			return err
		}
		_, err := s.Jobs.InsertTx(ctx, tx, jobs.Mail{Message: mail.ResetCode(who.Email, digits)}, nil)
		return err
	})
	if err != nil {
		return nil, err
	}
	return api.RequestReset202Response{}, nil
}

func (s *Server) ConfirmReset(ctx context.Context, r api.ConfirmResetRequestObject) (api.ConfirmResetResponseObject, error) {
	email := strings.ToLower(strings.TrimSpace(string(r.Body.Email)))
	who, err := s.Ory.FindByEmail(ctx, email)
	if err != nil {
		return nil, err
	}
	if who == nil {
		return nil, errWrongCode
	}
	reset, err := s.Q.Reset(ctx, who.ID)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, errWrongCode
	}
	if err != nil {
		return nil, err
	}
	if reset.DueAt != nil {
		return api.ConfirmReset204Response{}, nil
	}
	if reset.Tries >= codeTries || time.Now().After(reset.ExpiresAt) {
		return nil, errWrongCode
	}
	if subtle.ConstantTimeCompare(reset.CodeHash, hashCode(who.ID.String(), r.Body.Code)) != 1 {
		if err := s.Q.TryReset(ctx, who.ID); err != nil {
			return nil, err
		}
		return nil, errWrongCode
	}
	due := time.Now().Add(resetWait)
	err = s.tx(ctx, func(tx pgx.Tx) error {
		q := store.New(tx)
		if err := q.ScheduleReset(ctx, store.ScheduleResetParams{UserID: who.ID, DueAt: &due}); err != nil {
			return err
		}
		if _, err := s.Jobs.InsertTx(ctx, tx, jobs.SecondFactorReset{User: who.ID}, &river.InsertOpts{ScheduledAt: due}); err != nil {
			return err
		}
		_, err := s.Jobs.InsertTx(ctx, tx, jobs.Mail{Message: mail.ResetScheduled(who.Email, due.Format("2 January"))}, nil)
		return err
	})
	if err != nil {
		return nil, err
	}
	return api.ConfirmReset204Response{}, nil
}

func (s *Server) CancelReset(ctx context.Context, _ api.CancelResetRequestObject) (api.CancelResetResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	return api.CancelReset204Response{}, s.Q.EndReset(ctx, id.ID)
}
