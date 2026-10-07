package staff

import (
	"context"
	"net/http"
	"slices"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/notify"
	"github.com/sourcelocation/rondo/server/internal/problem"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// The kinds of staff action. Each declares what it needs and what it derives; see [Kind].

var freezeKind = &Kind{
	Name: "staff.freeze", System: true, Undoable: true, Target: Person,
	Undone: func(*Entry) *notify.Message {
		return &notify.Message{Kind: "staff_unfrozen", Presentation: notify.Toast, Title: "Your staff powers are back"}
	},
}

func init() {
	register(freezeKind)
	register(&Kind{
		Name: "role.grant", Target: Person, Fresh: true, Limit: 5, Undoable: true, Areas: []Area{Roles},
		Check: func(ctx context.Context, s *Service, actor *Access, e *Entry) error {
			role := e.Data["role"]
			if err := mayGrant(actor, role); err != nil {
				return err
			}
			held, err := store.New(s.Pool).RolesOf(ctx, *e.UserID)
			if err != nil {
				return err
			}
			if slices.Contains(held, role) {
				return problem.New(http.StatusConflict, api.Conflict, "They have that role already.")
			}
			return nil
		},
		Done:   func(e *Entry) *notify.Message { m := notify.RoleGranted(e.Data["role"]); return &m },
		Undone: func(e *Entry) *notify.Message { m := notify.RoleRemoved(e.Data["role"]); return &m },
	})
	register(&Kind{
		Name: "role.revoke", Target: Person, Fresh: true, Limit: 5, Undoable: true, Areas: []Area{Roles},
		Check: func(ctx context.Context, s *Service, actor *Access, e *Entry) error {
			role := e.Data["role"]
			if err := mayGrant(actor, role); err != nil {
				return err
			}
			held, err := store.New(s.Pool).RolesOf(ctx, *e.UserID)
			if err != nil {
				return err
			}
			if !slices.Contains(held, role) {
				return problem.New(http.StatusConflict, api.Conflict, "They don't have that role.")
			}
			return nil
		},
		Done:   func(e *Entry) *notify.Message { m := notify.RoleRemoved(e.Data["role"]); return &m },
		Undone: func(e *Entry) *notify.Message { m := notify.RoleGranted(e.Data["role"]); return &m },
	})
}

// mayGrant: a role can be granted and taken back by whoever holds roles.<role>.
func mayGrant(actor *Access, role Role) error {
	if !slices.Contains(Grantable, role) {
		return problem.Invalid("That role can't be granted.")
	}
	if !actor.Can("roles." + role) {
		return problem.Forbidden
	}
	return nil
}
