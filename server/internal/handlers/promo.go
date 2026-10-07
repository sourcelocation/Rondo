package handlers

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"golang.org/x/time/rate"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/pro"
	"github.com/sourcelocation/rondo/server/internal/problem"
	"github.com/sourcelocation/rondo/server/internal/staff"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// Promo codes: redeeming them, and the batches staff make.

var (
	errCode   = fail(http.StatusNotFound, api.CodeInvalid, "That code doesn't work.")
	errUsed   = fail(http.StatusConflict, api.CodeInvalid, "That code was used already.")
	errHasPro = fail(http.StatusConflict, api.AlreadyPro, "You have Pro already, and it renews.")
)

// redeemers limits how many codes each person may try: ten, then one every six minutes.
var redeemers sync.Map

func allowed(user uuid.UUID) bool {
	l, _ := redeemers.LoadOrStore(user, rate.NewLimiter(rate.Every(6*time.Minute), 10))
	return l.(*rate.Limiter).Allow()
}

func (s *Server) Redeem(ctx context.Context, r api.RedeemRequestObject) (api.RedeemResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	if !allowed(id.ID) {
		return nil, fail(http.StatusTooManyRequests, api.RateLimited, "That's a lot of codes. Try again in a while.")
	}
	u, err := s.user(ctx, id.ID)
	if err != nil {
		return nil, err
	}
	code := staff.NormalizeCode(r.Body.Code)
	c, err := s.Q.PromoCode(ctx, code)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, errCode
	}
	if err != nil {
		return nil, err
	}
	var batch map[string]string
	if err := json.Unmarshal(c.Data, &batch); err != nil {
		return nil, err
	}
	days, err := strconv.Atoi(batch["days"])
	if err != nil || c.BatchUndone || c.Revoked || (c.RedeemBy != nil && c.RedeemBy.Before(time.Now())) {
		return nil, errCode
	}
	renewing, err := s.Q.Renewing(ctx, id.ID)
	if err != nil {
		return nil, err
	}
	if renewing {
		return nil, errHasPro
	}
	start := time.Now()
	if pro.Active(u.ProUntil) {
		start = *u.ProUntil
	}
	end := start.AddDate(0, 0, days)
	err = s.tx(ctx, func(tx pgx.Tx) error {
		added, err := store.New(tx).AddGrant(ctx, store.AddGrantParams{Provider: pro.Promo, Ref: code, UserID: id.ID, PeriodEnd: &end})
		if err != nil {
			return err
		}
		if added == 0 {
			return errUsed
		}
		return s.recomputePro(ctx, tx, id.ID)
	})
	if err != nil {
		return nil, err
	}
	b, err := s.billing(ctx, id.ID)
	return api.Redeem200JSONResponse(b), err
}

func (s *Server) promoManager(ctx context.Context) error {
	_, a, err := s.access(ctx)
	if err != nil {
		return err
	}
	if a.Locked() {
		return staff.ErrPasskey
	}
	if !a.Can(staff.PromoManage) {
		return problem.Forbidden
	}
	return nil
}

func (s *Server) batch(ctx context.Context, b store.StaffAction, codes, used int) (api.PromoBatch, error) {
	e, err := staff.Read(b)
	if err != nil {
		return api.PromoBatch{}, err
	}
	days, _ := strconv.Atoi(e.Data["days"])
	out := api.PromoBatch{
		Id: e.ID, Days: days, Codes: codes, Used: used, RedeemBy: e.Until, CreatedAt: e.CreatedAt, Undone: e.UndoneAt != nil, Note: e.Note,
	}
	if e.ActorID != nil {
		refs, err := s.refs(ctx, *e.ActorID)
		if err != nil {
			return out, err
		}
		by := refs[*e.ActorID]
		out.By = &by
	}
	return out, nil
}

func (s *Server) ListPromoBatches(ctx context.Context, _ api.ListPromoBatchesRequestObject) (api.ListPromoBatchesResponseObject, error) {
	if err := s.promoManager(ctx); err != nil {
		return nil, err
	}
	rows, err := s.Q.PromoBatches(ctx)
	if err != nil {
		return nil, err
	}
	out := api.ListPromoBatches200JSONResponse{}
	for _, r := range rows {
		b := store.StaffAction{
			ID: r.ID, ActorID: r.ActorID, Kind: r.Kind, UserID: r.UserID, DeckID: r.DeckID, Reason: r.Reason, Note: r.Note, Until: r.Until,
			Data: r.Data, CaseID: r.CaseID, CreatedAt: r.CreatedAt, UndoneAt: r.UndoneAt, UndoneBy: r.UndoneBy, UndoReason: r.UndoReason,
		}
		item, err := s.batch(ctx, b, int(r.Codes), int(r.Used))
		if err != nil {
			return nil, err
		}
		out = append(out, item)
	}
	return out, nil
}

func (s *Server) GetPromoBatch(ctx context.Context, r api.GetPromoBatchRequestObject) (api.GetPromoBatchResponseObject, error) {
	if err := s.promoManager(ctx); err != nil {
		return nil, err
	}
	b, err := s.Q.Action(ctx, r.BatchId)
	if errors.Is(err, pgx.ErrNoRows) || (err == nil && b.Kind != "promo.batch") {
		return nil, errNotFound
	}
	if err != nil {
		return nil, err
	}
	rows, err := s.Q.BatchCodes(ctx, b.ID)
	if err != nil {
		return nil, err
	}
	var users []uuid.UUID
	used := 0
	for _, c := range rows {
		if c.UsedBy != nil {
			users = append(users, *c.UsedBy)
			used++
		}
	}
	refs, err := s.refs(ctx, users...)
	if err != nil {
		return nil, err
	}
	item, err := s.batch(ctx, b, len(rows), used)
	if err != nil {
		return nil, err
	}
	out := api.GetPromoBatch200JSONResponse{Batch: item, Codes: make([]api.PromoCodeItem, 0, len(rows))}
	for _, c := range rows {
		code := api.PromoCodeItem{Code: c.Code[:4] + "-" + c.Code[4:8] + "-" + c.Code[8:], Revoked: c.Revoked, UsedAt: c.UsedAt}
		if c.UsedBy != nil {
			ref := refs[*c.UsedBy]
			code.UsedBy = &ref
		}
		out.Codes = append(out.Codes, code)
	}
	return out, nil
}
