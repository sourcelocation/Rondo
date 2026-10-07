package handlers

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"net/netip"
	"slices"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/pro"
	"github.com/sourcelocation/rondo/server/internal/problem"
	"github.com/sourcelocation/rondo/server/internal/staff"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// Moderation as people meet it (restrictions refuse what reaches others) and as staff see people.

func active(until *time.Time) bool { return until != nil && until.After(time.Now()) }

func restriction(until time.Time) error {
	message := "Your account is restricted for good: you can't publish, share, invite or change your name."
	if until.Before(time.Now().AddDate(100, 0, 0)) {
		message = "Your account is restricted until " + until.Format("2 January") + ": you can't publish, share, invite or change your name."
	}
	return fail(http.StatusForbidden, api.Restricted, message)
}

var errNetwork = fail(http.StatusForbidden, api.NetworkRestricted, "Sharing isn't available from your network right now.")

// social runs before anything that shows a person to others or reaches people on their behalf
// (publishing, inviting, names, reports): it notes where they are, and refuses restricted people
// and restricted networks.
func (s *Server) social(ctx context.Context, user uuid.UUID) error {
	u, err := s.Q.User(ctx, user)
	if err != nil {
		return err
	}
	if active(u.RestrictedUntil) {
		return restriction(*u.RestrictedUntil)
	}
	ip := client(ctx).IP
	if !ip.IsValid() {
		return nil
	}
	if err := s.Q.Seen(ctx, store.SeenParams{UserID: user, Ip: ip}); err != nil {
		return err
	}
	restricted, err := s.Q.NetworkRestricted(ctx, ip)
	if err != nil {
		return err
	}
	if restricted {
		return errNetwork
	}
	return nil
}

// hiddenProfile: restricted and banned people don't appear to others.
func (s *Server) hiddenProfile(_ context.Context, u store.User) (bool, error) {
	return active(u.RestrictedUntil) || active(u.BannedUntil), nil
}

// seen notes where someone opens the app from (at most hourly per address) and the key of their
// email, so staff can find accounts that belong together.
func (s *Server) seen(ctx context.Context, user uuid.UUID, email string) error {
	if ip := client(ctx).IP; ip.IsValid() {
		if err := s.Q.SeenHourly(ctx, store.SeenHourlyParams{UserID: user, Ip: ip}); err != nil {
			return err
		}
	}
	if email == "" {
		return nil
	}
	key := s.emailKey(email)
	return s.Q.SetEmailKey(ctx, store.SetEmailKeyParams{ID: user, EmailKey: &key})
}

// emailKey is a keyed hash of an email as its inbox sees it: lowercased, without +tags, and for
// Gmail without dots, so one inbox's addresses share a key.
func (s *Server) emailKey(email string) string {
	local, domain, _ := strings.Cut(strings.ToLower(strings.TrimSpace(email)), "@")
	local, _, _ = strings.Cut(local, "+")
	if domain == "gmail.com" || domain == "googlemail.com" {
		local, domain = strings.ReplaceAll(local, ".", ""), "gmail.com"
	}
	mac := hmac.New(sha256.New, []byte(s.S.EmailKeySecret))
	mac.Write([]byte(local + "@" + domain))
	return hex.EncodeToString(mac.Sum(nil))
}

// Staff's view of people --------------------------------------------------------------------------

func (s *Server) staffItems(ctx context.Context, users []store.User) ([]api.StaffUserItem, error) {
	out := make([]api.StaffUserItem, 0, len(users))
	for _, u := range users {
		a, err := s.Staff.AccessOf(ctx, u.ID)
		if err != nil {
			return nil, err
		}
		out = append(out, api.StaffUserItem{
			User: ref(u), Joined: u.CreatedAt, Roles: a.Roles, RestrictedUntil: u.RestrictedUntil, BannedUntil: u.BannedUntil,
		})
	}
	return out, nil
}

func ref(u store.User) api.UserRef {
	return api.UserRef{Id: u.ID, Username: &u.Username, Name: u.Name, Flag: u.Flag}
}

func (s *Server) SearchUsers(ctx context.Context, r api.SearchUsersRequestObject) (api.SearchUsersResponseObject, error) {
	_, a, err := s.access(ctx)
	if err != nil {
		return nil, err
	}
	p := r.Params
	var users []store.User
	switch {
	case p.Email != nil:
		if !a.Can(staff.UsersEmail) {
			return nil, problem.Forbidden
		}
		who, err := s.Ory.FindByEmail(ctx, *p.Email)
		if err != nil {
			return nil, err
		}
		if who != nil {
			if users, err = s.Q.UsersByID(ctx, []uuid.UUID{who.ID}); err != nil {
				return nil, err
			}
		}
	case p.Ip != nil:
		if !a.Can(staff.UsersNetwork) {
			return nil, problem.Forbidden
		}
		prefix, err := netip.ParsePrefix(*p.Ip)
		if err != nil {
			ip, err := netip.ParseAddr(*p.Ip)
			if err != nil {
				return nil, problem.Invalid("That isn't an address or a range.")
			}
			prefix = netip.PrefixFrom(ip, ip.BitLen())
		}
		if users, err = s.Q.UsersAt(ctx, prefix.Masked()); err != nil {
			return nil, err
		}
	default:
		if !a.Can(staff.UsersSearch) {
			return nil, problem.Forbidden
		}
		q := strings.TrimPrefix(strings.TrimSpace(deref(p.Q)), "@")
		if q == "" {
			return api.SearchUsers200JSONResponse{}, nil
		}
		if users, err = s.Q.SearchUsers(ctx, q); err != nil {
			return nil, err
		}
	}
	items, err := s.staffItems(ctx, users)
	return api.SearchUsers200JSONResponse(items), err
}

func (s *Server) GetStaffUser(ctx context.Context, r api.GetStaffUserRequestObject) (api.GetStaffUserResponseObject, error) {
	_, a, err := s.access(ctx)
	if err != nil {
		return nil, err
	}
	if !a.Can(staff.UsersSearch) {
		return nil, problem.Forbidden
	}
	u, err := s.Q.User(ctx, r.UserId)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, errNotFound
	}
	if err != nil {
		return nil, err
	}
	them, err := s.Staff.AccessOf(ctx, u.ID)
	if err != nil {
		return nil, err
	}
	recent, err := s.Q.Ladder(ctx, &u.ID)
	if err != nil {
		return nil, err
	}
	kind, days, err := s.Staff.Suggest(ctx, u.ID)
	if err != nil {
		return nil, err
	}
	out := api.GetStaffUser200JSONResponse{
		User: ref(u), Joined: u.CreatedAt, Pro: pro.Active(u.ProUntil), Roles: them.Roles, RestrictedUntil: u.RestrictedUntil,
		BannedUntil: u.BannedUntil, Recent: int(recent), SuggestedKind: kind, SuggestedDays: days,
	}
	if a.Can(staff.UsersEmail) {
		who, err := s.Ory.Identity(ctx, u.ID)
		if err != nil {
			return nil, err
		}
		if who != nil {
			out.Email, out.EmailVerified = &who.Email, &who.Verified
			ways := slices.DeleteFunc(slices.Clone(who.Methods), func(m string) bool { return m == "oidc" })
			for _, p := range who.Providers {
				ways = append(ways, p.Provider)
			}
			slices.Sort(ways)
			out.SignIn = &ways
		}
	}
	if a.Can(staff.BillingView) {
		b, err := s.billing(ctx, u.ID)
		if err != nil {
			return nil, err
		}
		out.Billing = &b
	}
	if a.Can(staff.UsersNames) {
		if out.Names, err = s.namesOf(ctx, u.ID); err != nil {
			return nil, err
		}
	}
	if a.Can(staff.UsersNetwork) {
		if out.Ips, err = s.ipsOf(ctx, u.ID); err != nil {
			return nil, err
		}
		if out.Related, err = s.related(ctx, u); err != nil {
			return nil, err
		}
	}
	return out, nil
}

func (s *Server) namesOf(ctx context.Context, user uuid.UUID) (*[]api.NameUse, error) {
	rows, err := s.Q.NamesOf(ctx, user)
	if err != nil {
		return nil, err
	}
	out := make([]api.NameUse, 0, len(rows))
	for _, n := range rows {
		kind := "username"
		if n.Kind == 2 {
			kind = "name"
		}
		out = append(out, api.NameUse{Kind: kind, Value: n.Value, At: n.CreatedAt, ByStaff: n.ByStaff, Undone: n.Undone})
	}
	return &out, nil
}

func (s *Server) ipsOf(ctx context.Context, user uuid.UUID) (*[]api.IPUse, error) {
	rows, err := s.Q.IPsOf(ctx, user)
	if err != nil {
		return nil, err
	}
	out := make([]api.IPUse, 0, len(rows))
	for _, r := range rows {
		out = append(out, api.IPUse{Ip: strings.TrimSuffix(strings.TrimSuffix(r.Ip, "/32"), "/128"), FirstAt: r.FirstAt, LastAt: r.LastAt, Uses: int(r.Uses)})
	}
	return &out, nil
}

// related are accounts that may be the same person's: seen at the same address (IPv6: the same
// /64), signing in with the same inbox, or with names like theirs now or before.
func (s *Server) related(ctx context.Context, u store.User) (*[]api.Related, error) {
	why := map[uuid.UUID][]string{}
	var order []uuid.UUID
	note := func(id uuid.UUID, reason string) {
		if _, seen := why[id]; !seen {
			order = append(order, id)
		}
		if !slices.Contains(why[id], reason) {
			why[id] = append(why[id], reason)
		}
	}
	byIP, err := s.Q.RelatedByIP(ctx, u.ID)
	if err != nil {
		return nil, err
	}
	for _, r := range byIP {
		note(r.UserID, "address")
	}
	if u.EmailKey != nil {
		byEmail, err := s.Q.RelatedByEmail(ctx, store.RelatedByEmailParams{EmailKey: u.EmailKey, ID: u.ID})
		if err != nil {
			return nil, err
		}
		for _, id := range byEmail {
			note(id, "email")
		}
	}
	byName, err := s.Q.RelatedByName(ctx, u.ID)
	if err != nil {
		return nil, err
	}
	for _, id := range byName {
		note(id, "name")
	}
	users, err := s.Q.UsersByID(ctx, order)
	if err != nil {
		return nil, err
	}
	byID := map[uuid.UUID]store.User{}
	for _, other := range users {
		byID[other.ID] = other
	}
	out := make([]api.Related, 0, len(order))
	for _, id := range order {
		out = append(out, api.Related{User: ref(byID[id]), Why: why[id]})
	}
	return &out, nil
}
