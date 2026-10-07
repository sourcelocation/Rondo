package handlers

import (
	"context"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/sourcelocation/rondo/server/internal/api"
)

func redeem(ctx context.Context, s *Server, code string) (api.Billing, error) {
	res, err := s.Redeem(ctx, api.RedeemRequestObject{Body: &api.RedeemRequest{Code: code}})
	if err != nil {
		return api.Billing{}, err
	}
	return api.Billing(res.(api.Redeem200JSONResponse)), nil
}

func TestPromoCodes(t *testing.T) {
	s := server(t)
	ctx := context.Background()
	owner, bob, carol, dana := person(t, s, "Owner", false), person(t, s, "Bob", false), person(t, s, "Carol", false), person(t, s, "Dana", false)
	o := asStaff(owner, ownerEmail)
	batch, err := act(o, t, s, api.ActionRequest{Kind: "promo.batch", Note: ptr("Beta testers"), Data: &map[string]string{"count": "3", "days": "30"}})
	if err != nil {
		t.Fatal(err)
	}
	res, err := s.GetPromoBatch(o, api.GetPromoBatchRequestObject{BatchId: batch.Id})
	if err != nil {
		t.Fatal(err)
	}
	codes := res.(api.GetPromoBatch200JSONResponse).Codes
	if len(codes) != 3 || len(codes[0].Code) != 14 {
		t.Fatalf("codes: %+v", codes)
	}
	first := strings.ToLower(codes[0].Code)
	b, err := redeem(as(bob), s, first)
	if err != nil || !b.Pro || *b.Provider != api.Promo {
		t.Fatalf("redeemed: %+v %v", b, err)
	}
	if !slices.Contains(kinds(as(bob), t, s), "pro_started") {
		t.Fatal("Pro starts with a welcome")
	}
	if _, err := redeem(as(carol), s, first); code(err) != api.CodeInvalid {
		t.Fatalf("once: %v", err)
	}
	until := *b.ProUntil
	b, err = redeem(as(bob), s, codes[1].Code)
	if err != nil || !b.ProUntil.After(until.Add(29*24*time.Hour)) {
		t.Fatalf("a second code adds on: %v %v", b.ProUntil, err)
	}
	if _, err := act(o, t, s, api.ActionRequest{Kind: "promo.revoke", Data: &map[string]string{"code": codes[1].Code}}); err != nil {
		t.Fatal(err)
	}
	if u, _ := s.Q.User(ctx, bob); !u.ProUntil.Before(until.Add(time.Hour)) {
		t.Fatalf("revoking takes its days back: %v", u.ProUntil)
	}
	if _, err := s.Pool.Exec(ctx, `INSERT INTO subscriptions (provider, ref, user_id, product, status, period_end, auto_renew, sandbox)
		VALUES ('stripe', 'sub_1', $1, 'pro', 'active', now() + interval '20 days', true, false)`, dana); err != nil {
		t.Fatal(err)
	}
	if _, err := redeem(as(dana), s, codes[2].Code); code(err) != api.AlreadyPro {
		t.Fatalf("not while a subscription renews: %v", err)
	}
	undo(o, t, s, batch.Id)
	if _, err := redeem(as(carol), s, codes[2].Code); code(err) != api.CodeInvalid {
		t.Fatalf("an undone batch's codes stop working: %v", err)
	}
}

func TestGrantingPro(t *testing.T) {
	s := server(t)
	owner, eve := person(t, s, "Owner", false), person(t, s, "Eve", false)
	o := asStaff(owner, ownerEmail)
	until := time.Now().AddDate(0, 1, 0)
	granted, err := act(o, t, s, api.ActionRequest{Kind: "pro.grant", UserId: &eve, Until: &until})
	if err != nil {
		t.Fatal(err)
	}
	if !meOfCtx(as(eve), t, s).Pro {
		t.Fatal("granted Pro")
	}
	undo(o, t, s, granted.Id)
	if meOfCtx(as(eve), t, s).Pro {
		t.Fatal("undone, it's gone")
	}
}
