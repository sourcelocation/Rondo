package handlers

import (
	"context"
	"net/http"
	"strings"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/sourcelocation/dawl/subscription"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/notify"
	"github.com/sourcelocation/rondo/server/internal/pro"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// Billing: Stripe on the web, App Store and Google Play in the apps, all through dawl.

func (s *Server) billing(ctx context.Context, user uuid.UUID) (api.Billing, error) {
	u, err := s.Q.User(ctx, user)
	if err != nil {
		return api.Billing{}, err
	}
	out := api.Billing{Pro: pro.Active(u.ProUntil), ProUntil: u.ProUntil}
	subs, err := s.Q.Subscriptions(ctx, user)
	if err != nil {
		return out, err
	}
	if len(subs) > 0 {
		provider := api.BillingProvider(strings.ReplaceAll(subs[0].Provider, "_", "-"))
		out.Provider, out.Renews = &provider, &subs[0].AutoRenew
	}
	return out, nil
}

func (s *Server) GetBilling(ctx context.Context, _ api.GetBillingRequestObject) (api.GetBillingResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	if _, err := s.user(ctx, id.ID); err != nil {
		return nil, err
	}
	b, err := s.billing(ctx, id.ID)
	return api.GetBilling200JSONResponse(b), err
}

var errNoStore = fail(http.StatusServiceUnavailable, api.Unavailable, "Payments aren't set up here.")

func (s *Server) StripeCheckout(ctx context.Context, r api.StripeCheckoutRequestObject) (api.StripeCheckoutResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	price := s.S.StripePrices[string(r.Body.Plan)]
	if s.Stripe == nil || price == "" {
		return nil, errNoStore
	}
	u, err := s.user(ctx, id.ID)
	if err != nil {
		return nil, err
	}
	customer, err := s.Stripe.EnsureCustomer(ctx, id.ID.String(), id.Email, deref(u.StripeCustomer))
	if err != nil {
		return nil, err
	}
	if err := s.Q.SetStripeCustomer(ctx, store.SetStripeCustomerParams{ID: id.ID, StripeCustomer: &customer}); err != nil {
		return nil, err
	}
	link, err := s.Stripe.CheckoutURL(ctx, customer, id.ID.String(), price)
	return api.StripeCheckout200JSONResponse{Url: link}, err
}

func (s *Server) StripePortal(ctx context.Context, _ api.StripePortalRequestObject) (api.StripePortalResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	u, err := s.user(ctx, id.ID)
	if err != nil {
		return nil, err
	}
	if s.Stripe == nil || u.StripeCustomer == nil {
		return nil, errNoStore
	}
	link, err := s.Stripe.PortalURL(ctx, *u.StripeCustomer)
	return api.StripePortal200JSONResponse{Url: link}, err
}

func (s *Server) verify(ctx context.Context, gw subscription.Gateway, proof string) (api.Billing, error) {
	id, err := me(ctx)
	if err != nil {
		return api.Billing{}, err
	}
	state, err := gw.Verify(ctx, proof)
	if err != nil {
		return api.Billing{}, fail(http.StatusBadRequest, api.Invalid, "That purchase couldn't be verified.")
	}
	if state.Account != id.ID.String() {
		return api.Billing{}, fail(http.StatusForbidden, api.Forbidden, "That purchase belongs to another account.")
	}
	if err := s.Save(ctx, state); err != nil {
		return api.Billing{}, err
	}
	return s.billing(ctx, id.ID)
}

func (s *Server) VerifyAppStore(ctx context.Context, r api.VerifyAppStoreRequestObject) (api.VerifyAppStoreResponseObject, error) {
	if s.AppStore == nil {
		return nil, errNoStore
	}
	b, err := s.verify(ctx, s.AppStore, r.Body.Proof)
	return api.VerifyAppStore200JSONResponse(b), err
}

func (s *Server) VerifyGooglePlay(ctx context.Context, r api.VerifyGooglePlayRequestObject) (api.VerifyGooglePlayResponseObject, error) {
	if s.Play == nil {
		return nil, errNoStore
	}
	b, err := s.verify(ctx, s.Play, r.Body.Proof)
	return api.VerifyGooglePlay200JSONResponse(b), err
}

// Save stores a verified subscription and works out until when its owner has Pro; when Pro starts,
// they hear about it.
func (s *Server) Save(ctx context.Context, state subscription.State) error {
	user, err := uuid.Parse(state.Account)
	if err != nil {
		return nil //nolint:nilerr // a purchase without our account id can't be attributed; nothing to save
	}
	return s.tx(ctx, func(tx pgx.Tx) error {
		q := store.New(tx)
		if err := store.Ensure(ctx, q, user); err != nil {
			return err
		}
		if err := q.PutSubscription(ctx, store.PutSubscriptionParams{
			Provider: string(state.Provider), Ref: state.ProviderRef, UserID: user, Product: state.ProductID, Status: string(state.Status),
			PeriodEnd: state.CurrentPeriodEnd, AutoRenew: state.AutoRenew, Sandbox: state.Environment == "sandbox",
		}); err != nil {
			return err
		}
		return s.recomputePro(ctx, tx, user)
	})
}

// recomputePro works out until when someone has Pro, and tells them when it starts.
func (s *Server) recomputePro(ctx context.Context, tx pgx.Tx, user uuid.UUID) error {
	q := store.New(tx)
	started, err := pro.Recompute(ctx, q, user)
	if err != nil || !started {
		return err
	}
	u, err := q.User(ctx, user)
	if err != nil {
		return err
	}
	return s.Notify.Send(ctx, tx, user, notify.ProStarted(u.ProUntil))
}
