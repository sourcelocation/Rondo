package staff

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"slices"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/notify"
	"github.com/sourcelocation/rondo/server/internal/problem"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// Target is what an action is about.
type Target int

const (
	Nobody Target = iota
	Person
	Deck
)

// Kind is one kind of staff action: who may do it, what it's about, what it derives and what it
// tells people. Most kinds only declare their areas; Apply and Revert are for the few whose effect
// isn't derivable from the log alone.
type Kind struct {
	Name string
	// Permission needed; kinds that check their own (role grants) leave it empty.
	Permission Permission
	Target     Target
	// Reason: a reason is required and shown to the person.
	Reason bool
	// Fresh: needs a passkey sign-in within [Fresh].
	Fresh bool
	// Limit is how many an actor may do in an hour before they're frozen (the owner has none).
	Limit int
	// System kinds are Rondo's own (freezing someone over the limits); no one asks for them.
	System   bool
	Undoable bool
	// Outside: it may change whether someone can sign in, so Kratos, Hydra and Stripe must follow.
	Outside bool
	Areas   []Area
	Check   func(ctx context.Context, s *Service, actor *Access, e *Entry) error
	// Prepare runs in the transaction before the entry is written and may add to its data; Apply
	// runs after it's written; Revert when it's undone.
	Prepare func(ctx context.Context, s *Service, tx pgx.Tx, e *Entry) error
	Apply   func(ctx context.Context, s *Service, tx pgx.Tx, e *Entry) error
	Revert  func(ctx context.Context, s *Service, tx pgx.Tx, e *Entry) error
	// Done and Undone tell the person the action is about.
	Done, Undone func(e *Entry) *notify.Message
}

var kinds = map[string]*Kind{}

func register(k *Kind) { kinds[k.Name] = k }

// Entry is one staff action, as stored.
type Entry struct {
	store.StaffAction
	Data map[string]string
}

// Read decodes a stored entry.
func Read(row store.StaffAction) (*Entry, error) {
	e := &Entry{StaffAction: row, Data: map[string]string{}}
	if len(row.Data) > 0 {
		if err := json.Unmarshal(row.Data, &e.Data); err != nil {
			return nil, err
		}
	}
	return e, nil
}

// Request is what staff ask for.
type Request struct {
	Kind         string
	User, Deck   *uuid.UUID
	Reason, Note *string
	Until        *time.Time
	Data         map[string]string
	Case         *uuid.UUID
}

var (
	ErrPasskey   = problem.New(http.StatusForbidden, api.PasskeyRequired, "Sign in with your passkey to use staff tools.")
	errFresh     = problem.New(http.StatusForbidden, api.PasskeyRequired, "Confirm it's you with your passkey first.")
	errRank      = problem.New(http.StatusForbidden, api.Forbidden, "You can only act on people ranked below you.")
	errFrozen    = problem.New(http.StatusForbidden, api.Frozen, "Your staff powers are frozen: that's more than an hour allows.")
	errUndone    = problem.New(http.StatusConflict, api.Conflict, "That's already undone.")
	errNoUndo    = problem.New(http.StatusForbidden, api.Forbidden, "That can't be undone.")
	errNotYourUn = problem.New(http.StatusForbidden, api.Forbidden, "Only whoever did it, or someone ranked above them, can undo it.")
)

// Do runs a staff action: checks, the entry, what it derives and who it tells, in one transaction.
func (s *Service) Do(ctx context.Context, actor *Access, r Request) (*Entry, error) {
	k := kinds[r.Kind]
	if k == nil || k.System {
		return nil, problem.Invalid("There's no such action.")
	}
	if err := s.allowed(actor, k); err != nil {
		return nil, err
	}
	e := &Entry{StaffAction: store.StaffAction{
		ID: uuid.Must(uuid.NewV7()), ActorID: &actor.User, Kind: k.Name, Reason: r.Reason, Note: r.Note, Until: r.Until, CaseID: r.Case,
	}, Data: r.Data}
	if e.Data == nil {
		e.Data = map[string]string{}
	}
	switch k.Target {
	case Person:
		if r.User == nil {
			return nil, problem.Invalid("Say who it's about.")
		}
		if err := s.outranks(ctx, actor, *r.User); err != nil {
			return nil, err
		}
		e.UserID = r.User
	case Deck:
		if r.Deck == nil {
			return nil, problem.Invalid("Say which deck it's about.")
		}
		deck, err := store.New(s.Pool).Deck(ctx, *r.Deck)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, problem.NotFound
		}
		if err != nil {
			return nil, err
		}
		if deck.OwnerID != nil {
			if err := s.outranks(ctx, actor, *deck.OwnerID); err != nil {
				return nil, err
			}
		}
		e.DeckID, e.UserID = r.Deck, deck.OwnerID
		e.Data["deck"] = deck.Name
	}
	if r.Case != nil {
		if err := s.caseExists(ctx, *r.Case); err != nil {
			return nil, err
		}
	}
	if k.Reason && (r.Reason == nil || *r.Reason == "") {
		return nil, problem.Invalid("Give a reason; the person sees it.")
	}
	if k.Check != nil {
		if err := k.Check(ctx, s, actor, e); err != nil {
			return nil, err
		}
	}
	if err := s.limit(ctx, actor, k); err != nil {
		return nil, err
	}
	err := s.tx(ctx, func(tx pgx.Tx) error {
		if k.Prepare != nil {
			if err := k.Prepare(ctx, s, tx, e); err != nil {
				return err
			}
		}
		if err := s.insert(ctx, tx, e); err != nil {
			return err
		}
		if k.Apply != nil {
			if err := k.Apply(ctx, s, tx, e); err != nil {
				return err
			}
		}
		return s.settle(ctx, tx, k, e, k.Done)
	})
	if err != nil {
		return nil, err
	}
	if k.Outside && e.UserID != nil {
		s.follow(ctx, *e.UserID)
	}
	return e, nil
}

// outranks: [actor] may act on [user], who exists and ranks below them.
func (s *Service) outranks(ctx context.Context, actor *Access, user uuid.UUID) error {
	if _, err := store.New(s.Pool).User(ctx, user); errors.Is(err, pgx.ErrNoRows) {
		return problem.NotFound
	} else if err != nil {
		return err
	}
	rank, err := s.rankOf(ctx, &user)
	if err != nil {
		return err
	}
	if actor.Rank() <= rank || user == actor.User {
		return errRank
	}
	return nil
}

func (s *Service) allowed(actor *Access, k *Kind) error {
	switch {
	case actor.Frozen:
		return errFrozen
	case actor.Locked():
		return ErrPasskey
	case k.Permission != "" && !actor.Can(k.Permission):
		return problem.Forbidden
	case k.Fresh && !actor.fresh:
		return errFresh
	}
	return nil
}

func (s *Service) insert(ctx context.Context, tx pgx.Tx, e *Entry) error {
	data, err := json.Marshal(e.Data)
	if err != nil {
		return err
	}
	row, err := store.New(tx).InsertAction(ctx, store.InsertActionParams{
		ID: e.ID, ActorID: e.ActorID, Kind: e.Kind, UserID: e.UserID, DeckID: e.DeckID, Reason: e.Reason, Note: e.Note,
		Until: e.Until, Data: data, CaseID: e.CaseID,
	})
	e.StaffAction = row
	return err
}

// settle derives every area the kind touches and tells the person, after a change to the log.
func (s *Service) settle(ctx context.Context, tx pgx.Tx, k *Kind, e *Entry, tell func(*Entry) *notify.Message) error {
	areas := k.Areas
	if e.CaseID != nil {
		areas = append(slices.Clone(areas), Cases)
	}
	for _, area := range areas {
		if err := area.derive(ctx, s, tx, e); err != nil {
			return fmt.Errorf("deriving %s: %w", area.Name, err)
		}
	}
	if tell == nil || e.UserID == nil {
		return nil
	}
	if m := tell(e); m != nil {
		return s.Notify.Send(ctx, tx, *e.UserID, *m)
	}
	return nil
}

// Undo marks an entry undone and derives again. Whoever did it may undo it while they still hold
// its permission; so may anyone ranked above them with actions.revert, and above the person it's about.
func (s *Service) Undo(ctx context.Context, actor *Access, id uuid.UUID, reason string) (*Entry, error) {
	e, k, err := s.load(ctx, id)
	if err != nil {
		return nil, err
	}
	if err := s.mayUndo(ctx, actor, e, k); err != nil {
		return nil, err
	}
	if err := s.limit(ctx, actor, undoKind); err != nil {
		return nil, err
	}
	return e, s.undo(ctx, actor.User, e, k, reason)
}

var undoKind = &Kind{Name: "undo", Limit: 60}

func (s *Service) load(ctx context.Context, id uuid.UUID) (*Entry, *Kind, error) {
	row, err := store.New(s.Pool).Action(ctx, id)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil, problem.NotFound
	}
	if err != nil {
		return nil, nil, err
	}
	e, err := Read(row)
	if err != nil {
		return nil, nil, err
	}
	k := kinds[e.Kind]
	if k == nil {
		return nil, nil, fmt.Errorf("unknown staff action kind %q", e.Kind)
	}
	return e, k, nil
}

// MayUndo says whether [actor] may undo [e], for showing the button.
func (s *Service) MayUndo(ctx context.Context, actor *Access, e *Entry) bool {
	k := kinds[e.Kind]
	return k != nil && s.mayUndo(ctx, actor, e, k) == nil
}

func (s *Service) mayUndo(ctx context.Context, actor *Access, e *Entry, k *Kind) error {
	if e.UndoneAt != nil {
		return errUndone
	}
	if !k.Undoable {
		return errNoUndo
	}
	if actor.Frozen {
		return errFrozen
	}
	if actor.Locked() {
		return ErrPasskey
	}
	target, err := s.rankOf(ctx, e.UserID)
	if err != nil {
		return err
	}
	if e.UserID != nil && actor.Rank() <= target && !actor.Owner() {
		return errRank
	}
	own := e.ActorID != nil && *e.ActorID == actor.User && (k.Permission == "" || actor.Can(k.Permission))
	if own {
		return nil
	}
	rank, err := s.rankOf(ctx, e.ActorID)
	if err != nil {
		return err
	}
	if actor.Can(ActionsRevert) && actor.Rank() > rank {
		return nil
	}
	return errNotYourUn
}

func (s *Service) undo(ctx context.Context, by uuid.UUID, e *Entry, k *Kind, reason string) error {
	if k.Outside && e.UserID != nil {
		defer s.follow(ctx, *e.UserID)
	}
	return s.tx(ctx, func(tx pgx.Tx) error {
		n, err := store.New(tx).MarkUndone(ctx, store.MarkUndoneParams{ID: e.ID, UndoneBy: &by, UndoReason: &reason})
		if err != nil {
			return err
		}
		if n == 0 {
			return errUndone
		}
		now := time.Now()
		e.UndoneAt, e.UndoneBy, e.UndoReason = &now, &by, &reason
		if k.Revert != nil {
			if err := k.Revert(ctx, s, tx, e); err != nil {
				return err
			}
		}
		return s.settle(ctx, tx, k, e, k.Undone)
	})
}

// RevertSince undoes everything [who] did since [since] that can be undone: the cure after
// someone's account was misused. It's not subject to the hourly limits.
func (s *Service) RevertSince(ctx context.Context, actor *Access, who uuid.UUID, since time.Time, reason string) (int, error) {
	if !actor.Can(ActionsRevert) {
		return 0, problem.Forbidden
	}
	rank, err := s.rankOf(ctx, &who)
	if err != nil {
		return 0, err
	}
	if actor.Rank() <= rank {
		return 0, errRank
	}
	rows, err := store.New(s.Pool).ActionsSince(ctx, store.ActionsSinceParams{ActorID: &who, CreatedAt: since})
	if err != nil {
		return 0, err
	}
	n := 0
	for _, row := range rows {
		e, err := Read(row)
		if err != nil {
			return n, err
		}
		k := kinds[e.Kind]
		if k == nil || !k.Undoable {
			continue
		}
		if err := s.undo(ctx, actor.User, e, k, reason); err != nil && !errors.Is(err, errUndone) {
			return n, err
		}
		n++
	}
	return n, nil
}
