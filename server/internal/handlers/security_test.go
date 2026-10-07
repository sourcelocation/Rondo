package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/riverqueue/river"
	"github.com/sourcelocation/dawl/stripe"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/jobs"
	"github.com/sourcelocation/rondo/server/internal/ory"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// mailed is the code in the latest mail queued to [to].
func mailed(t *testing.T, s *Server, to string) string {
	t.Helper()
	var raw []byte
	err := s.Pool.QueryRow(context.Background(),
		"SELECT args FROM river_job WHERE kind = 'mail' AND args->>'To' = $1 ORDER BY id DESC LIMIT 1", to).Scan(&raw)
	if err != nil {
		t.Fatalf("no mail to %s: %v", to, err)
	}
	var job jobs.Mail
	if err := json.Unmarshal(raw, &job); err != nil {
		t.Fatal(err)
	}
	return job.Code
}

func identityJSON(id uuid.UUID, email string, credentials string) string {
	return fmt.Sprintf(`{"id": %q, "state": "active", "traits": {"email": %q},
		"verifiable_addresses": [{"value": %q, "verified": true}], "credentials": {%s}}`, id, email, email, credentials)
}

func TestChangingTheEmail(t *testing.T) {
	s, fake := serverWithOry(t)
	alice := person(t, s, "Alice", false)
	s.Stripe = stripe.New(stripe.Config{SecretKey: "sk_test_x"})
	customer := "cus_alice"
	if err := s.Q.SetStripeCustomer(context.Background(), store.SetStripeCustomerParams{ID: alice, StripeCustomer: &customer}); err != nil {
		t.Fatal(err)
	}
	stale := context.WithValue(context.Background(), identityKey{}, &ory.Session{ID: alice, Email: "alice@old.com", AuthenticatedAt: time.Now().Add(-time.Hour)})
	body := api.EmailChange{Email: "alice@new.com"}
	if _, err := s.ChangeEmail(stale, api.ChangeEmailRequestObject{Body: &body}); code(err) != api.Reauthenticate {
		t.Fatalf("needs a recent sign-in: %v", err)
	}
	ctx := context.WithValue(context.Background(), identityKey{}, &ory.Session{ID: alice, Email: "alice@old.com", AuthenticatedAt: time.Now()})
	if _, err := s.ChangeEmail(ctx, api.ChangeEmailRequestObject{Body: &body}); err != nil {
		t.Fatal(err)
	}
	right := mailed(t, s, "alice@new.com")
	for range 5 {
		if _, err := s.ConfirmEmail(ctx, api.ConfirmEmailRequestObject{Body: &api.CodeEntry{Code: "000000"}}); code(err) != api.CodeInvalid {
			t.Fatalf("a wrong code: %v", err)
		}
	}
	if _, err := s.ConfirmEmail(ctx, api.ConfirmEmailRequestObject{Body: &api.CodeEntry{Code: right}}); code(err) != api.CodeInvalid {
		t.Fatalf("five tries, then not even the right one: %v", err)
	}
	if _, err := s.ChangeEmail(ctx, api.ChangeEmailRequestObject{Body: &body}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.ConfirmEmail(ctx, api.ConfirmEmailRequestObject{Body: &api.CodeEntry{Code: mailed(t, s, "alice@new.com")}}); err != nil {
		t.Fatal(err)
	}
	if !fake.called("PATCH /admin/identities/" + alice.String()) {
		t.Fatal("Kratos moves the identity")
	}
	var told int
	_ = s.Pool.QueryRow(context.Background(), "SELECT count(*) FROM river_job WHERE kind = 'mail' AND args->>'To' = 'alice@old.com'").Scan(&told)
	if told != 1 {
		t.Fatal("the old address is told")
	}
	var receipts int
	_ = s.Pool.QueryRow(context.Background(), "SELECT count(*) FROM river_job WHERE kind = 'customer_email' AND args->>'customer' = 'cus_alice' AND args->>'email' = 'alice@new.com'").Scan(&receipts)
	if receipts != 1 {
		t.Fatal("Stripe's receipts follow")
	}
}

type tokens struct{}

func (tokens) Subject(_ context.Context, provider, token, _ string) (string, error) {
	return provider + "-" + token, nil
}

func TestLinkingGoogleAndApple(t *testing.T) {
	s, fake := serverWithOry(t)
	s.Tokens = tokens{}
	alice := person(t, s, "Alice", false)
	fake.replies["GET /admin/identities/"+alice.String()] = identityJSON(alice, "alice@example.com",
		`"code": {"identifiers": ["alice@example.com"]}, "oidc": {"identifiers": ["apple:a1"], "config": {"providers": [{"provider": "apple", "subject": "a1"}]}}`)
	if _, err := s.Link(as(alice), api.LinkRequestObject{Body: &api.LinkRequest{Provider: api.LinkRequestProviderGoogle, IdToken: "g1"}}); err != nil {
		t.Fatal(err)
	}
	if !fake.called("PATCH /admin/identities/" + alice.String()) {
		t.Fatal("linking adds the credential")
	}
	if _, err := s.Unlink(as(alice), api.UnlinkRequestObject{Provider: api.UnlinkParamsProviderApple}); err != nil {
		t.Fatal(err)
	}
	if !fake.called("DELETE /admin/identities/" + alice.String() + "/credentials/oidc?identifier=apple%3Aa1") {
		t.Fatal("unlinking removes it")
	}
}

func TestALostSecondStepGoesAWeekLater(t *testing.T) {
	s, fake := serverWithOry(t)
	bob := uuid.Must(uuid.NewV7())
	fake.replies["GET /admin/identities"] = fmt.Sprintf(`[{"id": %q}]`, bob)
	fake.replies["GET /admin/identities/"+bob.String()] = identityJSON(bob, "bob@example.com", `"code": {"identifiers": ["bob@example.com"]}, "totp": {"identifiers": []}`)
	ctx := context.Background()
	if _, err := s.RequestReset(ctx, api.RequestResetRequestObject{Body: &api.ResetRequest{Email: "bob@example.com"}}); err != nil {
		t.Fatal(err)
	}
	confirm := api.ResetConfirm{Email: "bob@example.com", Code: mailed(t, s, "bob@example.com")}
	if _, err := s.ConfirmReset(ctx, api.ConfirmResetRequestObject{Body: &confirm}); err != nil {
		t.Fatal(err)
	}
	if me := meOfCtx(as(bob), t, s); me.SecondFactorResetAt == nil || me.SecondFactorResetAt.Before(time.Now().Add(6*24*time.Hour)) {
		t.Fatalf("due in a week, and the app says so: %v", me.SecondFactorResetAt)
	}
	withCode := context.WithValue(ctx, identityKey{}, &ory.Session{ID: bob, Methods: []ory.Method{{Method: "totp", CompletedAt: time.Now()}}})
	if me := meOfCtx(withCode, t, s); me.SecondFactorResetAt != nil {
		t.Fatal("signing in with it cancels")
	}
	if _, err := s.RequestReset(from(ctx, "198.51.100.1"), api.RequestResetRequestObject{Body: &api.ResetRequest{Email: "bob@example.com"}}); err != nil {
		t.Fatal(err)
	}
	confirm.Code = mailed(t, s, "bob@example.com")
	if _, err := s.ConfirmReset(ctx, api.ConfirmResetRequestObject{Body: &confirm}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Pool.Exec(ctx, "UPDATE second_factor_resets SET due_at = now() - interval '1 minute'"); err != nil {
		t.Fatal(err)
	}
	worker := &jobs.SecondFactorResetWorker{Pool: s.Pool, Ory: s.Ory, Jobs: s.Jobs}
	if err := worker.Work(ctx, &river.Job[jobs.SecondFactorReset]{Args: jobs.SecondFactorReset{User: bob}}); err != nil {
		t.Fatal(err)
	}
	if !fake.called("DELETE /admin/identities/" + bob.String() + "/credentials/totp") {
		t.Fatal("when due, the second step goes")
	}
}
