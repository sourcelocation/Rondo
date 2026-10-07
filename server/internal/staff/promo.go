package staff

import (
	"context"
	"crypto/rand"
	"errors"
	"math/big"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/sourcelocation/dawl/subscription"

	"github.com/sourcelocation/rondo/server/internal/notify"
	"github.com/sourcelocation/rondo/server/internal/pro"
	"github.com/sourcelocation/rondo/server/internal/problem"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// Pro that isn't bought: granted by staff, or from promo codes in batches.

// ProArea: until when someone has Pro, from all their subscriptions; they hear when it starts.
var ProArea = Area{"pro", func(ctx context.Context, s *Service, tx pgx.Tx, e *Entry) error {
	if e.UserID == nil {
		return nil
	}
	q := store.New(tx)
	started, err := pro.Recompute(ctx, q, *e.UserID)
	if err != nil || !started {
		return err
	}
	u, err := q.User(ctx, *e.UserID)
	if err != nil {
		return err
	}
	return s.Notify.Send(ctx, tx, *e.UserID, notify.ProStarted(u.ProUntil))
}}

// alphabet is Crockford's base 32: no I, L, O or U, so codes read back without mistakes.
const alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

// NewCode is a code of twelve characters (60 bits).
func NewCode() string {
	var b strings.Builder
	for range 12 {
		n, err := rand.Int(rand.Reader, big.NewInt(int64(len(alphabet))))
		if err != nil {
			panic(err)
		}
		b.WriteByte(alphabet[n.Int64()])
	}
	return b.String()
}

// NormalizeCode reads a code as typed: any case, with dashes and spaces, and the letters people
// mistake for digits.
func NormalizeCode(typed string) string {
	r := strings.NewReplacer("-", "", " ", "", "I", "1", "L", "1", "O", "0")
	return r.Replace(strings.ToUpper(strings.TrimSpace(typed)))
}

func positive(data map[string]string, key string, most int) (int, error) {
	n, err := strconv.Atoi(data[key])
	if err != nil || n < 1 || n > most {
		return 0, problem.Invalid("Say how many, from 1 to " + strconv.Itoa(most) + ".")
	}
	return n, nil
}

func init() {
	register(&Kind{
		Name: "promo.batch", Permission: PromoManage, Fresh: true, Limit: 5, Undoable: true,
		Check: func(_ context.Context, _ *Service, _ *Access, e *Entry) error {
			if _, err := positive(e.Data, "count", 500); err != nil {
				return err
			}
			_, err := positive(e.Data, "days", 9999)
			return err
		},
		Apply: func(ctx context.Context, _ *Service, tx pgx.Tx, e *Entry) error {
			q := store.New(tx)
			count, _ := strconv.Atoi(e.Data["count"])
			for range count {
				if err := q.AddPromoCode(ctx, store.AddPromoCodeParams{Code: NewCode(), BatchID: e.ID}); err != nil {
					return err
				}
			}
			return nil
		},
	})
	register(&Kind{
		Name: "promo.revoke", Permission: PromoManage, Limit: 30, Undoable: true, Areas: []Area{ProArea},
		Prepare: func(ctx context.Context, _ *Service, tx pgx.Tx, e *Entry) error {
			code := NormalizeCode(e.Data["code"])
			q := store.New(tx)
			if _, err := q.PromoCode(ctx, code); errors.Is(err, pgx.ErrNoRows) {
				return problem.NotFound
			} else if err != nil {
				return err
			}
			e.Data["code"] = code
			if used, err := q.Redemption(ctx, code); err == nil {
				e.UserID = &used.UserID
			} else if !errors.Is(err, pgx.ErrNoRows) {
				return err
			}
			return nil
		},
		Apply: func(ctx context.Context, _ *Service, tx pgx.Tx, e *Entry) error {
			q := store.New(tx)
			if err := q.RevokeCode(ctx, store.RevokeCodeParams{Code: e.Data["code"], RevokedBy: &e.ID}); err != nil {
				return err
			}
			return q.SetSubscriptionStatus(ctx, store.SetSubscriptionStatusParams{Provider: pro.Promo, Ref: e.Data["code"], Status: string(subscription.StatusRevoked)})
		},
		Revert: func(ctx context.Context, _ *Service, tx pgx.Tx, e *Entry) error {
			q := store.New(tx)
			if err := q.UnrevokeCode(ctx, store.UnrevokeCodeParams{Code: e.Data["code"], RevokedBy: &e.ID}); err != nil {
				return err
			}
			return q.SetSubscriptionStatus(ctx, store.SetSubscriptionStatusParams{Provider: pro.Promo, Ref: e.Data["code"], Status: string(subscription.StatusActive)})
		},
	})
	register(&Kind{
		Name: "pro.grant", Permission: ProGrant, Target: Person, Limit: 20, Undoable: true, Areas: []Area{ProArea},
		Check: func(_ context.Context, _ *Service, _ *Access, e *Entry) error {
			if e.Until == nil || e.Until.Before(time.Now()) {
				return problem.Invalid("Say until when.")
			}
			return nil
		},
		Apply: func(ctx context.Context, _ *Service, tx pgx.Tx, e *Entry) error {
			_, err := store.New(tx).AddGrant(ctx, store.AddGrantParams{Provider: pro.Grant, Ref: e.ID.String(), UserID: *e.UserID, PeriodEnd: e.Until})
			return err
		},
		Revert: func(ctx context.Context, _ *Service, tx pgx.Tx, e *Entry) error {
			return store.New(tx).SetSubscriptionStatus(ctx, store.SetSubscriptionStatusParams{Provider: pro.Grant, Ref: e.ID.String(), Status: string(subscription.StatusRevoked)})
		},
	})
}
