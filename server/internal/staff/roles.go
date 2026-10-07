// Package staff is who may do what to whom, and the log every staff change goes through.
//
// Roles grant permissions; code checks permissions, never roles. Rank (owner > admin > moderator)
// decides whom you may act on: only people strictly below you. Every change is an entry in
// staff_actions, and what entries decide (roles, standing, removed decks, names, granted Pro) is
// derived from the entries not undone: doing is inserting and deriving, undoing is marking and
// deriving, so one path serves both, and expiry too.
package staff

import (
	"slices"
	"time"

	"github.com/google/uuid"

	"github.com/sourcelocation/rondo/server/internal/ory"
)

// Role is a staff role. The owner's comes from RONDO_OWNER_EMAIL; the rest are granted.
type Role = string

const (
	Owner     Role = "owner"
	Admin     Role = "admin"
	Moderator Role = "moderator"
	// Support and Curator are defined for what comes next and can't be granted yet.
	Support Role = "support"
	Curator Role = "curator"
)

// Grantable roles: what the Team page offers.
var Grantable = []Role{Admin, Moderator}

// Permission is one staff tool. They reach the apps as these strings.
type Permission = string

const (
	UsersSearch    Permission = "users.search"
	UsersWarn      Permission = "users.warn"
	UsersRestrict  Permission = "users.restrict"
	UsersBan       Permission = "users.ban"
	UsersRename    Permission = "users.rename"
	DecksRemove    Permission = "decks.remove"
	CasesReview    Permission = "cases.review"
	UsersEmail     Permission = "users.email"
	UsersNetwork   Permission = "users.network"
	UsersNames     Permission = "users.names"
	NetworkLimit   Permission = "network.restrict"
	BillingView    Permission = "billing.view"
	ProGrant       Permission = "pro.grant"
	PromoManage    Permission = "promo.manage"
	LogView        Permission = "log.view"
	ActionsRevert  Permission = "actions.revert"
	RolesModerator Permission = "roles.moderator"
	RolesAdmin     Permission = "roles.admin"
	RolesSupport   Permission = "roles.support"
	RolesCurator   Permission = "roles.curator"
	DecksOfficial  Permission = "decks.official"
)

var moderating = []Permission{UsersSearch, UsersWarn, UsersRestrict, UsersBan, UsersRename, DecksRemove, CasesReview}

var administering = append(slices.Clone(moderating),
	UsersEmail, UsersNetwork, UsersNames, NetworkLimit, BillingView, ProGrant, PromoManage, LogView, ActionsRevert,
	RolesModerator, RolesSupport, RolesCurator)

var permissions = map[Role][]Permission{
	Owner:     append(slices.Clone(administering), RolesAdmin),
	Admin:     administering,
	Moderator: moderating,
	Support:   {UsersSearch, UsersEmail, BillingView, ProGrant},
	Curator:   {DecksOfficial},
}

var ranks = map[Role]int{Owner: 3, Admin: 2, Moderator: 1}

// Fresh is how recent a passkey sign-in has to be for the actions that matter most.
const Fresh = 15 * time.Minute

// Access is what one person may do now.
type Access struct {
	User   uuid.UUID
	Roles  []Role
	Frozen bool
	// How this session signed in: with a passkey at all, and within [Fresh].
	passkey, fresh bool
}

// Rank is the highest rank among the roles: 0 for everyone without one that ranks.
func (a *Access) Rank() int {
	r := 0
	for _, role := range a.Roles {
		r = max(r, ranks[role])
	}
	return r
}

// Owner says whether this is the owner.
func (a *Access) Owner() bool { return slices.Contains(a.Roles, Owner) }

// has says whether a role grants [p], whatever the session.
func (a *Access) has(p Permission) bool {
	for _, role := range a.Roles {
		if slices.Contains(permissions[role], p) {
			return true
		}
	}
	return false
}

// Can says whether [p] is usable now: granted, not frozen, and in a session signed in with a passkey.
func (a *Access) Can(p Permission) bool { return a.passkey && !a.Frozen && a.has(p) }

// Locked: the person has a role, but this session didn't sign in with a passkey.
func (a *Access) Locked() bool { return len(a.Roles) > 0 && !a.passkey }

// Permissions are what [Can] allows, for the apps to show the right buttons.
func (a *Access) Permissions() []Permission {
	var out []Permission
	for _, role := range a.Roles {
		for _, p := range permissions[role] {
			if a.Can(p) && !slices.Contains(out, p) {
				out = append(out, p)
			}
		}
	}
	slices.Sort(out)
	return out
}

func newAccess(user uuid.UUID, roles []Role, frozen bool, session *ory.Session, skipPasskey bool) *Access {
	a := &Access{User: user, Roles: roles, Frozen: frozen && !slices.Contains(roles, Owner)}
	if session != nil {
		a.passkey = skipPasskey || session.Passkey(0)
		a.fresh = skipPasskey || session.Passkey(Fresh)
	}
	return a
}
