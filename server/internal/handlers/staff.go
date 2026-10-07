package handlers

import (
	"context"
	"errors"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/ory"
	"github.com/sourcelocation/rondo/server/internal/problem"
	"github.com/sourcelocation/rondo/server/internal/staff"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// Staff: the team, and the log every staff change goes through (internal/staff).

// access is the signed-in person's staff access; it refuses people without any role.
func (s *Server) access(ctx context.Context) (*ory.Session, *staff.Access, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, nil, err
	}
	a, err := s.Staff.Access(ctx, id)
	if err != nil {
		return nil, nil, err
	}
	if len(a.Roles) == 0 {
		return nil, nil, problem.Forbidden
	}
	return id, a, nil
}

func (s *Server) ListTeam(ctx context.Context, _ api.ListTeamRequestObject) (api.ListTeamResponseObject, error) {
	if _, _, err := s.access(ctx); err != nil {
		return nil, err
	}
	rows, err := s.Q.Team(ctx)
	if err != nil {
		return nil, err
	}
	roles := map[uuid.UUID][]string{}
	ids := []uuid.UUID{}
	if owner, ok := s.Staff.OwnerID(ctx); ok {
		roles[owner] = []string{staff.Owner}
		ids = append(ids, owner)
	}
	for _, r := range rows {
		if _, seen := roles[r.UserID]; !seen {
			ids = append(ids, r.UserID)
		}
		roles[r.UserID] = append(roles[r.UserID], r.Roles...)
	}
	refs, err := s.refs(ctx, ids...)
	if err != nil {
		return nil, err
	}
	out := api.ListTeam200JSONResponse{}
	for _, id := range ids {
		out = append(out, api.TeamMember{User: refs[id], Roles: roles[id]})
	}
	return out, nil
}

// refs names people for staff pages.
func (s *Server) refs(ctx context.Context, ids ...uuid.UUID) (map[uuid.UUID]api.UserRef, error) {
	rows, err := s.Q.UserRefs(ctx, ids)
	if err != nil {
		return nil, err
	}
	out := make(map[uuid.UUID]api.UserRef, len(ids))
	for _, id := range ids {
		out[id] = api.UserRef{Id: id}
	}
	for _, r := range rows {
		out[r.ID] = api.UserRef{Id: r.ID, Name: r.Name, Username: &r.Username, Flag: r.Flag}
	}
	return out, nil
}

// actions turns log entries into what the API answers, naming people and decks, and saying for each
// whether [viewer] may undo it.
func (s *Server) actions(ctx context.Context, viewer *staff.Access, rows []store.StaffAction) ([]api.StaffAction, error) {
	var ids []uuid.UUID
	for _, r := range rows {
		for _, id := range []*uuid.UUID{r.ActorID, r.UserID, r.UndoneBy} {
			if id != nil {
				ids = append(ids, *id)
			}
		}
	}
	refs, err := s.refs(ctx, ids...)
	if err != nil {
		return nil, err
	}
	ref := func(id *uuid.UUID) *api.UserRef {
		if id == nil {
			return nil
		}
		r := refs[*id]
		return &r
	}
	out := make([]api.StaffAction, 0, len(rows))
	for _, row := range rows {
		e, err := staff.Read(row)
		if err != nil {
			return nil, err
		}
		a := api.StaffAction{
			Id: e.ID, Kind: e.Kind, Actor: ref(e.ActorID), User: ref(e.UserID), DeckId: e.DeckID, Reason: e.Reason, Note: e.Note,
			Until: e.Until, Data: e.Data, CaseId: e.CaseID, CreatedAt: e.CreatedAt, UndoneAt: e.UndoneAt, UndoneBy: ref(e.UndoneBy),
			UndoReason: e.UndoReason, Undoable: s.Staff.MayUndo(ctx, viewer, e),
		}
		if e.DeckID != nil {
			if d, err := s.Q.Deck(ctx, *e.DeckID); err == nil {
				a.DeckName = &d.Name
			}
		}
		out = append(out, a)
	}
	return out, nil
}

func (s *Server) ListActions(ctx context.Context, r api.ListActionsRequestObject) (api.ListActionsResponseObject, error) {
	_, a, err := s.access(ctx)
	if err != nil {
		return nil, err
	}
	p := r.Params
	own := p.Actor != nil && *p.Actor == a.User
	if !a.Can(staff.LogView) && p.User == nil && p.Deck == nil && !own {
		return nil, problem.Forbidden
	}
	if a.Locked() {
		return nil, staff.ErrPasskey
	}
	rows, err := s.Q.ActionLog(ctx, store.ActionLogParams{Actor: p.Actor, Target: p.User, Deck: p.Deck, Kind: p.Kind, Before: p.Before})
	if err != nil {
		return nil, err
	}
	more := len(rows) > 50
	if more {
		rows = rows[:50]
	}
	items, err := s.actions(ctx, a, rows)
	if err != nil {
		return nil, err
	}
	return api.ListActions200JSONResponse{Items: items, More: more}, nil
}

func (s *Server) action(ctx context.Context, a *staff.Access, id uuid.UUID) (api.StaffAction, error) {
	row, err := s.Q.Action(ctx, id)
	if errors.Is(err, pgx.ErrNoRows) {
		return api.StaffAction{}, errNotFound
	}
	if err != nil {
		return api.StaffAction{}, err
	}
	out, err := s.actions(ctx, a, []store.StaffAction{row})
	if err != nil {
		return api.StaffAction{}, err
	}
	return out[0], nil
}

func (s *Server) DoAction(ctx context.Context, r api.DoActionRequestObject) (api.DoActionResponseObject, error) {
	_, a, err := s.access(ctx)
	if err != nil {
		return nil, err
	}
	b := r.Body
	data := map[string]string{}
	if b.Data != nil {
		data = *b.Data
	}
	e, err := s.Staff.Do(ctx, a, staff.Request{
		Kind: b.Kind, User: b.UserId, Deck: b.DeckId, Reason: b.Reason, Note: b.Note, Until: b.Until, Data: data, Case: b.CaseId,
	})
	if err != nil {
		return nil, err
	}
	out, err := s.action(ctx, a, e.ID)
	return api.DoAction201JSONResponse(out), err
}

func (s *Server) GetAction(ctx context.Context, r api.GetActionRequestObject) (api.GetActionResponseObject, error) {
	_, a, err := s.access(ctx)
	if err != nil {
		return nil, err
	}
	if a.Locked() {
		return nil, staff.ErrPasskey
	}
	out, err := s.action(ctx, a, r.ActionId)
	return api.GetAction200JSONResponse(out), err
}

func (s *Server) UndoAction(ctx context.Context, r api.UndoActionRequestObject) (api.UndoActionResponseObject, error) {
	_, a, err := s.access(ctx)
	if err != nil {
		return nil, err
	}
	if _, err := s.Staff.Undo(ctx, a, r.ActionId, r.Body.Reason); err != nil {
		return nil, err
	}
	out, err := s.action(ctx, a, r.ActionId)
	return api.UndoAction200JSONResponse(out), err
}

func (s *Server) RevertActions(ctx context.Context, r api.RevertActionsRequestObject) (api.RevertActionsResponseObject, error) {
	_, a, err := s.access(ctx)
	if err != nil {
		return nil, err
	}
	n, err := s.Staff.RevertSince(ctx, a, r.Body.ActorId, r.Body.Since, r.Body.Reason)
	return api.RevertActions200JSONResponse{Undone: n}, err
}
