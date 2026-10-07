package handlers

import (
	"context"
	"slices"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/ory"
)

func grant(ctx context.Context, t *testing.T, s *Server, user uuid.UUID, role string) (api.StaffAction, error) {
	t.Helper()
	res, err := s.DoAction(ctx, api.DoActionRequestObject{Body: &api.DoActionJSONRequestBody{Kind: "role.grant", UserId: &user, Data: &map[string]string{"role": role}}})
	if err != nil {
		return api.StaffAction{}, err
	}
	return api.StaffAction(res.(api.DoAction201JSONResponse)), nil
}

func meOfCtx(ctx context.Context, t *testing.T, s *Server) api.Me {
	t.Helper()
	res, err := s.GetMe(ctx, api.GetMeRequestObject{})
	if err != nil {
		t.Fatal(err)
	}
	return api.Me(res.(api.GetMe200JSONResponse))
}

func TestTheOwnerComesFromTheEnvironmentAndNeedsAPasskey(t *testing.T) {
	s := server(t)
	owner := person(t, s, "Owner", false)
	me := meOfCtx(asStaff(owner, ownerEmail), t, s)
	if !slices.Equal(*me.Roles, []string{"owner"}) || !slices.Contains(*me.Permissions, "roles.admin") || *me.StaffLocked {
		t.Fatalf("owner with a passkey: %v %v %v", *me.Roles, *me.Permissions, *me.StaffLocked)
	}
	plain := context.WithValue(context.Background(), identityKey{}, &ory.Session{ID: owner, Email: ownerEmail, EmailVerified: true})
	me = meOfCtx(plain, t, s)
	if len(*me.Permissions) != 0 || !*me.StaffLocked {
		t.Fatalf("without a passkey the tools stay locked: %v %v", *me.Permissions, *me.StaffLocked)
	}
	impostor := person(t, s, "Impostor", false)
	unverified := context.WithValue(context.Background(), identityKey{}, &ory.Session{ID: impostor, Email: ownerEmail, Methods: []ory.Method{{Method: "passkey", CompletedAt: time.Now()}}})
	if me := meOfCtx(unverified, t, s); len(*me.Roles) != 0 {
		t.Fatalf("an unverified owner address grants nothing: %v", *me.Roles)
	}
}

func TestRolesFollowRanks(t *testing.T) {
	s := server(t)
	owner, admin, mod, bob := person(t, s, "Owner", false), person(t, s, "Ada", false), person(t, s, "Mo", false), person(t, s, "Bob", false)
	o := asStaff(owner, ownerEmail)
	if _, err := grant(o, t, s, admin, "admin"); err != nil {
		t.Fatal(err)
	}
	if _, err := grant(o, t, s, mod, "moderator"); err != nil {
		t.Fatal(err)
	}
	a, m := asStaff(admin, "ada@example.com"), asStaff(mod, "mo@example.com")
	if _, err := grant(a, t, s, bob, "admin"); code(err) != api.Forbidden {
		t.Fatalf("only the owner makes admins: %v", err)
	}
	if _, err := grant(m, t, s, bob, "moderator"); code(err) != api.Forbidden {
		t.Fatalf("moderators don't grant roles: %v", err)
	}
	if _, err := grant(a, t, s, owner, "moderator"); code(err) != api.Forbidden {
		t.Fatalf("nobody acts on the owner: %v", err)
	}
	if _, err := grant(a, t, s, bob, "moderator"); err != nil {
		t.Fatalf("admins make moderators: %v", err)
	}
	if _, err := grant(a, t, s, bob, "support"); code(err) != api.Invalid {
		t.Fatalf("support isn't grantable yet: %v", err)
	}
	stale := context.WithValue(context.Background(), identityKey{}, &ory.Session{
		ID: admin, Email: "ada@example.com", Methods: []ory.Method{{Method: "passkey", CompletedAt: time.Now().Add(-time.Hour)}},
	})
	if _, err := grant(stale, t, s, person(t, s, "Cy", false), "moderator"); code(err) != api.PasskeyRequired {
		t.Fatalf("role grants need a fresh passkey: %v", err)
	}
	team, err := s.ListTeam(m, api.ListTeamRequestObject{})
	if err != nil || len(team.(api.ListTeam200JSONResponse)) != 4 {
		t.Fatalf("team: %v %v", team, err)
	}
	for _, member := range team.(api.ListTeam200JSONResponse) {
		if member.User.Username == nil {
			t.Fatal("people are named by their username, with or without a display name")
		}
	}
}

func TestUndoingDerivesAgain(t *testing.T) {
	s := server(t)
	owner, mod := person(t, s, "Owner", false), person(t, s, "Mo", false)
	o := asStaff(owner, ownerEmail)
	ctx := context.Background()
	before, _ := s.Q.RolesOf(ctx, mod)
	done, err := grant(o, t, s, mod, "moderator")
	if err != nil {
		t.Fatal(err)
	}
	if roles, _ := s.Q.RolesOf(ctx, mod); !slices.Equal(roles, []string{"moderator"}) {
		t.Fatalf("granted: %v", roles)
	}
	if me := meOfCtx(as(mod), t, s); len(*me.Notifications) != 1 || (*me.Notifications)[0].Presentation != "modal" {
		t.Fatalf("told once, as a modal: %+v", *me.Notifications)
	}
	undone, err := s.UndoAction(o, api.UndoActionRequestObject{ActionId: done.Id, Body: &api.UndoRequest{Reason: "by mistake"}})
	if err != nil || undone.(api.UndoAction200JSONResponse).UndoneAt == nil {
		t.Fatalf("undo: %v %v", undone, err)
	}
	if after, _ := s.Q.RolesOf(ctx, mod); !slices.Equal(after, before) {
		t.Fatalf("undoing restores what was: %v, want %v", after, before)
	}
	if _, err := s.UndoAction(o, api.UndoActionRequestObject{ActionId: done.Id, Body: &api.UndoRequest{Reason: "again"}}); code(err) != api.Conflict {
		t.Fatalf("undone once: %v", err)
	}
	me := meOfCtx(as(mod), t, s)
	for _, n := range *me.Notifications {
		if _, err := s.MarkSeen(as(mod), api.MarkSeenRequestObject{NotificationId: n.Id}); err != nil {
			t.Fatal(err)
		}
	}
	if me := meOfCtx(as(mod), t, s); len(*me.Notifications) != 0 {
		t.Fatalf("seen notifications aren't shown again: %v", *me.Notifications)
	}
}

func TestGoingOverTheHourlyLimitFreezesStaff(t *testing.T) {
	s := server(t)
	owner, admin := person(t, s, "Owner", false), person(t, s, "Ada", false)
	o := asStaff(owner, ownerEmail)
	if _, err := grant(o, t, s, admin, "admin"); err != nil {
		t.Fatal(err)
	}
	a := asStaff(admin, "ada@example.com")
	start := time.Now().Add(-time.Second)
	for i := range 5 {
		if _, err := grant(a, t, s, person(t, s, "M", false), "moderator"); err != nil {
			t.Fatalf("grant %d: %v", i, err)
		}
	}
	if _, err := grant(a, t, s, person(t, s, "M", false), "moderator"); code(err) != api.Frozen {
		t.Fatalf("the sixth in an hour freezes: %v", err)
	}
	if me := meOfCtx(a, t, s); len(*me.Permissions) != 0 {
		t.Fatalf("frozen staff have no tools: %v", *me.Permissions)
	}
	var jobs int
	_ = s.Pool.QueryRow(context.Background(), "SELECT count(*) FROM river_job WHERE kind = 'notify_email'").Scan(&jobs)
	if me := meOfCtx(o, t, s); len(*me.Notifications) == 0 || jobs != 1 {
		t.Fatalf("the owner hears about it, by email too: %v %d", *me.Notifications, jobs)
	}
	reverted, err := s.RevertActions(o, api.RevertActionsRequestObject{Body: &api.RevertRequest{ActorId: admin, Since: start, Reason: "account misused"}})
	if err != nil || reverted.(api.RevertActions200JSONResponse).Undone != 5 {
		t.Fatalf("revert: %v %v", reverted, err)
	}
	if team, _ := s.Q.Team(context.Background()); len(team) != 1 {
		t.Fatalf("only the admin keeps a role: %v", team)
	}
	log, err := s.ListActions(o, api.ListActionsRequestObject{Params: api.ListActionsParams{Kind: ptr("staff.freeze")}})
	if err != nil {
		t.Fatal(err)
	}
	freeze := log.(api.ListActions200JSONResponse).Items[0]
	if _, err := s.UndoAction(o, api.UndoActionRequestObject{ActionId: freeze.Id, Body: &api.UndoRequest{Reason: "sorted"}}); err != nil {
		t.Fatal(err)
	}
	if me := meOfCtx(a, t, s); len(*me.Permissions) == 0 {
		t.Fatal("undoing the freeze gives the tools back")
	}
}

func TestTheLogIsForStaffWhoMayReadIt(t *testing.T) {
	s := server(t)
	owner, mod, bob := person(t, s, "Owner", false), person(t, s, "Mo", false), person(t, s, "Bob", false)
	if _, err := grant(asStaff(owner, ownerEmail), t, s, mod, "moderator"); err != nil {
		t.Fatal(err)
	}
	m := asStaff(mod, "mo@example.com")
	if _, err := s.ListActions(m, api.ListActionsRequestObject{}); code(err) != api.Forbidden {
		t.Fatalf("moderators don't read the whole log: %v", err)
	}
	if _, err := s.ListActions(m, api.ListActionsRequestObject{Params: api.ListActionsParams{User: &bob}}); err != nil {
		t.Fatalf("but do read about one person: %v", err)
	}
	if _, err := s.ListActions(as(bob), api.ListActionsRequestObject{Params: api.ListActionsParams{User: &bob}}); code(err) != api.Forbidden {
		t.Fatalf("people without a role read nothing: %v", err)
	}
}
