package staff

import (
	"context"
	"errors"
	"net/http"
	"net/netip"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/riverqueue/river"
	"github.com/sourcelocation/dawl/subscription"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/names"
	"github.com/sourcelocation/rondo/server/internal/notify"
	"github.com/sourcelocation/rondo/server/internal/problem"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// Moderation: warnings, restrictions, bans, name resets, decks off Discover and restricted networks.

// Reasons staff give; people are told them (notify.Reason has their words).
var Reasons = []string{"spam", "harassment", "hate", "sexual", "impersonation", "copyright", "illegal", "name", "evasion", "other"}

// Standing: restricted and banned until, from user.restrict, user.ban and user.lift entries. A ban
// that ends is followed when it ends.
var Standing = Area{"standing", func(ctx context.Context, s *Service, tx pgx.Tx, e *Entry) error {
	row, err := store.New(tx).DeriveStanding(ctx, e.UserID)
	if err != nil {
		return err
	}
	if end := row.BannedUntil; end != nil && end.After(time.Now()) && end.Before(time.Now().AddDate(100, 0, 0)) {
		_, err = s.Jobs.InsertTx(ctx, tx, Reconcile{User: *e.UserID}, &river.InsertOpts{ScheduledAt: end.Add(time.Second)})
	}
	return err
}}

// Publication: whether a deck was taken off Discover.
var Publication = Area{"publication", func(ctx context.Context, _ *Service, tx pgx.Tx, e *Entry) error {
	return store.New(tx).DerivePublication(ctx, *e.DeckID)
}}

// Names: someone's username and display name are their newest names not undone. A name staff set
// can be replaced at once.
var Names = Area{"names", func(ctx context.Context, _ *Service, tx pgx.Tx, e *Entry) error {
	q := store.New(tx)
	username, err := q.CurrentName(ctx, store.CurrentNameParams{UserID: *e.UserID, Kind: 1})
	if err != nil {
		return err
	}
	display, err := q.CurrentName(ctx, store.CurrentNameParams{UserID: *e.UserID, Kind: 2})
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return err
	}
	u, err := q.User(ctx, *e.UserID)
	if err != nil {
		return err
	}
	changed := u.UsernameChangedAt
	if username.ByStaff || e.UndoneAt != nil {
		changed = nil
	}
	err = q.SetNames(ctx, store.SetNamesParams{ID: *e.UserID, Username: *username.Value, Name: display.Value, UsernameChangedAt: changed})
	var pg *pgconn.PgError
	if errors.As(err, &pg) && pg.ConstraintName == "users_username" {
		return problem.New(http.StatusConflict, api.Conflict, "Their old username was taken since.")
	}
	return err
}}

// Network: a restricted address range, while the entry isn't undone.
var Network = Area{"network", func(ctx context.Context, _ *Service, tx pgx.Tx, e *Entry) error {
	q := store.New(tx)
	if err := q.DeriveNetwork(ctx, e.ID); err != nil || e.UndoneAt != nil {
		return err
	}
	prefix, err := netip.ParsePrefix(e.Data["range"])
	if err != nil {
		return err
	}
	return q.AddNetwork(ctx, store.AddNetworkParams{ActionID: e.ID, Range: prefix, Until: e.Until})
}}

// Reconcile follows someone's standing outside Rondo's database (see [Service.Outside]): run after
// bans, lifts and their undos when it couldn't be at once, and when a ban ends.
type Reconcile struct {
	User uuid.UUID `json:"user"`
}

func (Reconcile) Kind() string { return "staff_reconcile" }

type ReconcileWorker struct {
	river.WorkerDefaults[Reconcile]
	Staff *Service
}

func (w *ReconcileWorker) Work(ctx context.Context, job *river.Job[Reconcile]) error {
	return w.Staff.Outside(ctx, job.Args.User)
}

// follow makes the outside match now, or soon: a failure is retried by a job.
func (s *Service) follow(ctx context.Context, user uuid.UUID) {
	if err := s.Outside(ctx, user); err != nil {
		if s.Log != nil {
			s.Log.Warn("following a standing change", "user", user, "err", err)
		}
		_, _ = s.Jobs.Insert(context.WithoutCancel(ctx), Reconcile{User: user}, nil)
	}
}

// Outside makes Kratos, Hydra and the stores match someone's standing. Banned, they can't sign in,
// every session ends, agents lose access, and subscriptions stop renewing where the store lets the
// server say so. Not banned, they can sign in again and those renewals are back on.
func (s *Service) Outside(ctx context.Context, user uuid.UUID) error {
	q := store.New(s.Pool)
	u, err := q.User(ctx, user)
	if err != nil {
		return err
	}
	banned := u.BannedUntil != nil && u.BannedUntil.After(time.Now())
	if err := s.Ory.SetActive(ctx, user, !banned); err != nil {
		return err
	}
	if banned {
		if err := s.Ory.RevokeConsent(ctx, user, ""); err != nil {
			return err
		}
		return s.stopRenewals(ctx, q, user)
	}
	return s.resumeRenewals(ctx, q, user)
}

// stopRenewals turns off the renewal of someone's subscriptions, remembering which, so they can be
// turned back on.
func (s *Service) stopRenewals(ctx context.Context, q *store.Queries, user uuid.UUID) error {
	subs, err := q.RenewingSubscriptions(ctx, user)
	if err != nil {
		return err
	}
	for _, sub := range subs {
		renewer := s.Renewers[subscription.Provider(sub.Provider)]
		if renewer == nil {
			continue
		}
		if err := renewer.SetAutoRenew(ctx, sub.Ref, false); errors.Is(err, subscription.ErrNotFound) {
			continue
		} else if err != nil {
			return err
		}
		if err := q.SetRenewalStopped(ctx, store.SetRenewalStoppedParams{Provider: sub.Provider, Ref: sub.Ref, RenewalStopped: true}); err != nil {
			return err
		}
	}
	return nil
}

// resumeRenewals turns back on the renewals a ban turned off, for subscriptions still in their
// period. Only an unreachable store is tried again: one that refuses leaves it to the person.
func (s *Service) resumeRenewals(ctx context.Context, q *store.Queries, user uuid.UUID) error {
	subs, err := q.StoppedRenewals(ctx, user)
	if err != nil {
		return err
	}
	for _, sub := range subs {
		state := subscription.State{Status: subscription.Status(sub.Status), CurrentPeriodEnd: sub.PeriodEnd}
		if renewer := s.Renewers[subscription.Provider(sub.Provider)]; renewer != nil && state.Entitles(time.Now()) {
			if err := renewer.SetAutoRenew(ctx, sub.Ref, true); errors.Is(err, subscription.ErrUnavailable) {
				return err
			} else if err != nil && s.Log != nil {
				s.Log.Warn("resuming a renewal", "user", user, "ref", sub.Ref, "err", err)
			}
		}
		if err := q.SetRenewalStopped(ctx, store.SetRenewalStoppedParams{Provider: sub.Provider, Ref: sub.Ref, RenewalStopped: false}); err != nil {
			return err
		}
	}
	return nil
}

// Suggest is the next step for someone, from what they got in the last 180 days: two warnings, a
// week's restriction, a month's, then for good, then a ban.
func (s *Service) Suggest(ctx context.Context, user uuid.UUID) (kind string, days *int, err error) {
	n, err := store.New(s.Pool).Ladder(ctx, &user)
	if err != nil {
		return "", nil, err
	}
	week, month := 7, 30
	switch {
	case n < 2:
		return "user.warn", nil, nil
	case n == 2:
		return "user.restrict", &week, nil
	case n == 3:
		return "user.restrict", &month, nil
	case n == 4:
		return "user.restrict", nil, nil
	default:
		return "user.ban", nil, nil
	}
}

func reason(_ context.Context, _ *Service, _ *Access, e *Entry) error {
	if e.Reason != nil && !slices.Contains(Reasons, *e.Reason) {
		return problem.Invalid("That isn't one of the reasons.")
	}
	if e.Until != nil && e.Until.Before(time.Now()) {
		return problem.Invalid("That ends before it starts.")
	}
	return nil
}

func init() {
	register(&Kind{
		Name: "user.warn", Permission: UsersWarn, Target: Person, Reason: true, Limit: 60, Undoable: true, Check: reason,
		Done:   func(e *Entry) *notify.Message { m := notify.Warned(*e.Reason, e.Note); return &m },
		Undone: func(*Entry) *notify.Message { m := notify.Withdrawn(); return &m },
	})
	register(&Kind{
		Name: "user.restrict", Permission: UsersRestrict, Target: Person, Reason: true, Limit: 30, Undoable: true,
		Areas: []Area{Standing}, Check: reason,
		Done:   func(e *Entry) *notify.Message { m := notify.Restricted(e.Until, *e.Reason, e.Note); return &m },
		Undone: func(*Entry) *notify.Message { m := notify.Lifted(); return &m },
	})
	register(&Kind{
		Name: "user.ban", Permission: UsersBan, Target: Person, Reason: true, Limit: 10, Undoable: true, Outside: true,
		Areas: []Area{Standing}, Check: reason,
		Done:   func(e *Entry) *notify.Message { m := notify.Banned(e.Until, *e.Reason, e.Note); return &m },
		Undone: func(*Entry) *notify.Message { m := notify.Lifted(); return &m },
	})
	register(&Kind{
		Name: "user.lift", Permission: UsersRestrict, Target: Person, Limit: 30, Undoable: true, Outside: true,
		Areas: []Area{Standing},
		Done:  func(*Entry) *notify.Message { m := notify.Lifted(); return &m },
	})
	register(&Kind{
		Name: "user.rename", Permission: UsersRename, Target: Person, Reason: true, Limit: 30, Undoable: true,
		Areas: []Area{Names}, Check: reason,
		Prepare: func(ctx context.Context, _ *Service, tx pgx.Tx, e *Entry) error {
			u, err := store.New(tx).User(ctx, *e.UserID)
			e.Data["from"] = u.Username
			return err
		},
		Apply: func(ctx context.Context, _ *Service, tx pgx.Tx, e *Entry) error {
			q := store.New(tx)
			name := names.Random()
			for range 8 {
				if _, err := q.UserByUsername(ctx, name); errors.Is(err, pgx.ErrNoRows) {
					break
				}
				name = names.Random()
			}
			if err := q.AddName(ctx, store.AddNameParams{ID: uuid.Must(uuid.NewV7()), UserID: *e.UserID, Kind: 1, Value: &name, ActionID: &e.ID}); err != nil {
				return err
			}
			return q.AddName(ctx, store.AddNameParams{ID: uuid.Must(uuid.NewV7()), UserID: *e.UserID, Kind: 2, ActionID: &e.ID})
		},
		Revert: func(ctx context.Context, _ *Service, tx pgx.Tx, e *Entry) error {
			return store.New(tx).UndoNames(ctx, &e.ID)
		},
		Done:   func(*Entry) *notify.Message { m := notify.Renamed(); return &m },
		Undone: func(*Entry) *notify.Message { m := notify.NameBack(); return &m },
	})
	register(&Kind{
		Name: "deck.remove", Permission: DecksRemove, Target: Deck, Reason: true, Limit: 30, Undoable: true,
		Areas: []Area{Publication}, Check: reason,
		Prepare: func(ctx context.Context, s *Service, tx pgx.Tx, e *Entry) error {
			if e.Data["take_down"] != "true" {
				return nil
			}
			q := store.New(tx)
			shares, err := q.SharesOf(ctx, *e.DeckID)
			if err != nil {
				return err
			}
			var kept []string
			for _, sh := range shares {
				kept = append(kept, sh.UserID.String()+":"+strconv.Itoa(int(sh.Role)))
				if err := s.Notify.Send(ctx, tx, sh.UserID, notify.TakenDown(e.Data["deck"])); err != nil {
					return err
				}
			}
			e.Data["shares"] = strings.Join(kept, ",")
			return q.DeleteShares(ctx, *e.DeckID)
		},
		Revert: func(ctx context.Context, _ *Service, tx pgx.Tx, e *Entry) error {
			q := store.New(tx)
			for _, kept := range strings.Split(e.Data["shares"], ",") {
				user, role, _ := strings.Cut(kept, ":")
				id, err := uuid.Parse(user)
				n, _ := strconv.ParseInt(role, 10, 16)
				if err != nil || n < 1 {
					continue
				}
				if err := q.AddShare(ctx, store.AddShareParams{DeckID: *e.DeckID, UserID: id, Role: int16(n)}); err != nil {
					return err
				}
			}
			return nil
		},
		Done:   func(e *Entry) *notify.Message { m := notify.DeckRemoved(e.Data["deck"], *e.Reason, e.Note); return &m },
		Undone: func(e *Entry) *notify.Message { m := notify.DeckBack(e.Data["deck"]); return &m },
	})
	register(&Kind{
		Name: "network.restrict", Permission: NetworkLimit, Fresh: true, Limit: 5, Undoable: true, Areas: []Area{Network},
		Check: func(_ context.Context, _ *Service, _ *Access, e *Entry) error {
			prefix, err := netip.ParsePrefix(e.Data["range"])
			if err != nil {
				if ip, err := netip.ParseAddr(e.Data["range"]); err == nil {
					prefix = netip.PrefixFrom(ip, ip.BitLen())
				} else {
					return problem.Invalid("That isn't an address or a range.")
				}
			}
			if (prefix.Addr().Is4() && prefix.Bits() < 16) || (prefix.Addr().Is6() && prefix.Bits() < 32) {
				return problem.Invalid("That range is too wide.")
			}
			e.Data["range"] = prefix.Masked().String()
			return nil
		},
	})
}
