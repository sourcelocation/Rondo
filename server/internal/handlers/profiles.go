package handlers

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/netip"
	"slices"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/hashicorp/golang-lru/v2/expirable"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	openapi_types "github.com/oapi-codegen/runtime/types"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/names"
	"github.com/sourcelocation/rondo/server/internal/pro"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// Profiles: usernames, display names, flags, and the public page with a person's decks and activity.

// usernameEvery is how often a chosen username can change.
const usernameEvery = 30 * 24 * time.Hour

type clientKey struct{}

// Client is where a request comes from: Cloudflare's view of it in production.
type Client struct {
	IP      netip.Addr
	Country string
}

// WithClient keeps where a request comes from for handlers: Cloudflare's CF-Connecting-IP and
// CF-IPCountry (only Cloudflare reaches the server), else the connection's address.
func WithClient(r *http.Request) *http.Request {
	var c Client
	if ip, err := netip.ParseAddr(r.Header.Get("CF-Connecting-IP")); err == nil {
		c.IP = ip.Unmap()
	} else if host, _, err := net.SplitHostPort(r.RemoteAddr); err == nil {
		if ip, err := netip.ParseAddr(host); err == nil {
			c.IP = ip.Unmap()
		}
	}
	if country, ok := names.Flag(r.Header.Get("CF-IPCountry")); ok {
		c.Country = country
	}
	return r.WithContext(context.WithValue(r.Context(), clientKey{}, c))
}

func client(ctx context.Context) Client {
	c, _ := ctx.Value(clientKey{}).(Client)
	return c
}

// display is how people see someone: the display name, else the username.
func display(u store.User) *string {
	if u.Name != nil {
		return u.Name
	}
	return &u.Username
}

func meOf(u store.User) api.Me {
	confirmed := u.ProfileConfirmedAt != nil
	m := api.Me{
		Id: u.ID, Name: u.Name, Pro: pro.Active(u.ProUntil), ProUntil: u.ProUntil, Username: &u.Username, Flag: u.Flag,
		ProfileConfirmed: &confirmed, ActivityHidden: &u.ActivityHidden,
	}
	if next := usernameChanges(u); next != nil {
		m.UsernameChangesAt = next
	}
	return m
}

// usernameChanges is when someone's username may change next: nil when it may now.
func usernameChanges(u store.User) *time.Time {
	if names.Automatic(u.Username) || u.UsernameChangedAt == nil {
		return nil
	}
	next := u.UsernameChangedAt.Add(usernameEvery)
	if next.Before(time.Now()) {
		return nil
	}
	return &next
}

var (
	errUsernameTaken = fail(http.StatusConflict, api.UsernameTaken, "That username is taken.")
	errUsernameHeld  = fail(http.StatusConflict, api.UsernameTaken, "That was someone's username until recently.")
	errProfile       = fail(http.StatusForbidden, api.ProfileRequired, "Set up your profile first, so people know who's sharing.")
)

func usernameRefusal(why string) error {
	message := map[string]string{
		names.Invalid:  "A username has 3 to 20 lowercase letters, digits and underscores.",
		names.Reserved: "That username is reserved.",
		names.Rude:     "Pick another username.",
	}[why]
	return fail(http.StatusBadRequest, api.UsernameInvalid, message)
}

// free says whether [name] (already valid) can be [user]'s: nobody else's, and not someone's until
// less than a month ago.
func (s *Server) free(ctx context.Context, user uuid.UUID, name string) (string, error) {
	other, err := s.Q.UserByUsername(ctx, name)
	switch {
	case err == nil && other.ID != user:
		return names.Taken, nil
	case err != nil && !errors.Is(err, pgx.ErrNoRows):
		return "", err
	}
	held, err := s.Q.UsernameHeld(ctx, store.UsernameHeldParams{Value: &name, UserID: user})
	if err != nil || !held {
		return "", err
	}
	return names.Held, nil
}

func (s *Server) UpdateProfile(ctx context.Context, r api.UpdateProfileRequestObject) (api.UpdateProfileResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	u, err := s.profile(ctx, id.ID, *r.Body)
	if err != nil {
		return nil, err
	}
	return api.UpdateProfile200JSONResponse(meOf(u)), nil
}

// SetName is the older way to set a display name; it confirms nothing.
func (s *Server) SetName(ctx context.Context, r api.SetNameRequestObject) (api.SetNameResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	u, err := s.profile(ctx, id.ID, api.ProfileUpdate{Name: &r.Body.Name})
	if err != nil {
		return nil, err
	}
	return api.SetName200JSONResponse(meOf(u)), nil
}

// profile applies a profile change, recording every new name.
func (s *Server) profile(ctx context.Context, user uuid.UUID, b api.ProfileUpdate) (store.User, error) {
	u, err := s.user(ctx, user)
	if err != nil {
		return u, err
	}
	if err := s.social(ctx, user); err != nil {
		return u, err
	}
	p := store.SetProfileParams{
		ID: user, Username: u.Username, UsernameChangedAt: u.UsernameChangedAt, Name: u.Name, Flag: u.Flag, ActivityHidden: u.ActivityHidden,
	}
	var added []store.AddNameParams
	if b.Username != nil {
		name, why := names.Username(*b.Username)
		if name != u.Username {
			if why != "" {
				return u, usernameRefusal(why)
			}
			if next := usernameChanges(u); next != nil {
				return u, fail(http.StatusForbidden, api.TooSoon, "You can change your username again on "+next.Format("2 January")+".")
			}
			why, err := s.free(ctx, user, name)
			if err != nil {
				return u, err
			}
			if why == names.Taken {
				return u, errUsernameTaken
			}
			if why == names.Held {
				return u, errUsernameHeld
			}
			now := time.Now()
			p.Username, p.UsernameChangedAt = name, &now
			added = append(added, store.AddNameParams{UserID: user, Kind: 1, Value: &name})
		}
	}
	if b.Name != nil {
		var name *string
		if trimmed := strings.TrimSpace(*b.Name); trimmed != "" {
			clean, ok := names.DisplayName(trimmed)
			if !ok {
				return u, fail(http.StatusBadRequest, api.Invalid, "A display name has 1 to 60 characters; pick another.")
			}
			name = &clean
		}
		if !equal(name, u.Name) {
			p.Name = name
			added = append(added, store.AddNameParams{UserID: user, Kind: 2, Value: name})
		}
	}
	if b.Flag != nil {
		p.Flag = nil
		if *b.Flag != "" {
			flag, ok := names.Flag(*b.Flag)
			if !ok {
				return u, fail(http.StatusBadRequest, api.Invalid, "That isn't a country.")
			}
			p.Flag = &flag
		}
	}
	if b.ActivityHidden != nil {
		p.ActivityHidden = *b.ActivityHidden
	}
	if b.Confirm != nil && *b.Confirm {
		now := time.Now()
		p.ProfileConfirmedAt = &now
	}
	err = s.tx(ctx, func(tx pgx.Tx) error {
		q := store.New(tx)
		if u, err = q.SetProfile(ctx, p); err != nil {
			return err
		}
		for _, n := range added {
			n.ID = uuid.Must(uuid.NewV7())
			if err := q.AddName(ctx, n); err != nil {
				return err
			}
		}
		return nil
	})
	var pg *pgconn.PgError
	if errors.As(err, &pg) && pg.ConstraintName == "users_username" {
		return u, errUsernameTaken
	}
	return u, err
}

func equal(a, b *string) bool { return (a == nil) == (b == nil) && (a == nil || *a == *b) }

func (s *Server) CheckUsername(ctx context.Context, r api.CheckUsernameRequestObject) (api.CheckUsernameResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	name, why := names.Username(r.Username)
	out := api.CheckUsername200JSONResponse{Username: name}
	if why == "" {
		if why, err = s.free(ctx, id.ID, name); err != nil {
			return nil, err
		}
	}
	if why == "" {
		out.Available = true
		return out, nil
	}
	out.Reason = &why
	if why == names.Taken || why == names.Held {
		base := name[:min(len(name), 17)]
		for range 10 {
			candidate := base + names.Random()[len("user")+5:]
			if _, w := names.Username(candidate); w != "" {
				continue
			}
			if w, err := s.free(ctx, id.ID, candidate); err == nil && w == "" {
				out.Suggestion = &candidate
				break
			}
		}
	}
	return out, nil
}

func (s *Server) GetProfile(ctx context.Context, r api.GetProfileRequestObject) (api.GetProfileResponseObject, error) {
	name := strings.ToLower(strings.TrimPrefix(r.Username, "@"))
	u, err := s.Q.UserByUsername(ctx, name)
	var movedFrom *string
	if errors.Is(err, pgx.ErrNoRows) {
		now, err := s.Q.MovedUsername(ctx, &name)
		if err != nil {
			return nil, errNotFound
		}
		if u, err = s.Q.UserByUsername(ctx, now); err != nil {
			return nil, errNotFound
		}
		movedFrom = &name
	} else if err != nil {
		return nil, err
	}
	if hidden, err := s.hiddenProfile(ctx, u); err != nil || hidden {
		return nil, errNotFound
	}
	decks, err := s.items(ctx, "AND p.listed AND d.owner_id = $4", "", "", 0, u.ID)
	if err != nil {
		return nil, err
	}
	out := api.GetProfile200JSONResponse{
		Id: u.ID, Username: u.Username, Name: u.Name, Flag: u.Flag, Joined: u.CreatedAt, Pro: pro.Active(u.ProUntil),
		MovedFrom: movedFrom, Decks: append([]api.DiscoverItem{}, decks[:min(len(decks), 30)]...),
	}
	if !u.ActivityHidden {
		if out.Activity, err = s.activity(ctx, u.ID, r.Params.Year); err != nil {
			return nil, err
		}
	}
	return out, nil
}

// Activity ----------------------------------------------------------------------------------------

type day struct {
	date    time.Time
	reviews int
	ms      int64
}

// activities keeps each person's days for an hour: profiles are read far more than they change.
var activities = expirable.NewLRU[uuid.UUID, []day](10_000, nil, time.Hour)

// days are a person's days with reviews, oldest first, and today, in their time zone with days
// starting at 4 am.
func (s *Server) days(ctx context.Context, user uuid.UUID) ([]day, time.Time, error) {
	zone := "UTC"
	if tz, err := s.Q.Timezone(ctx, user); err == nil && tz != nil {
		if _, err := time.LoadLocation(*tz); err == nil {
			zone = *tz
		}
	}
	loc, _ := time.LoadLocation(zone)
	t := time.Now().In(loc).Add(-4 * time.Hour)
	today := time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, time.UTC)
	if cached, ok := activities.Get(user); ok {
		return cached, today, nil
	}
	rows, err := s.Q.ReviewDays(ctx, store.ReviewDaysParams{Zone: zone, UserID: user})
	if err != nil {
		return nil, today, err
	}
	out := make([]day, 0, len(rows))
	for _, r := range rows {
		out = append(out, day{date: time.Date(r.Day.Year(), r.Day.Month(), r.Day.Day(), 0, 0, 0, 0, time.UTC), reviews: int(r.Reviews), ms: r.Ms})
	}
	activities.Add(user, out)
	return out, today, nil
}

// activity sums a person's reviews: all time, in one year ([year], else this one or the latest
// with reviews), and in streaks of days.
func (s *Server) activity(ctx context.Context, user uuid.UUID, year *int) (*api.Activity, error) {
	days, today, err := s.days(ctx, user)
	if err != nil {
		return nil, err
	}
	a := &api.Activity{Year: today.Year(), Years: []int{}, Days: []api.ActivityDay{}}
	switch {
	case year != nil:
		a.Year = *year
	case len(days) > 0 && days[len(days)-1].date.Year() < a.Year:
		a.Year = days[len(days)-1].date.Year()
	}
	run, ms := 0, int64(0)
	var last time.Time
	for _, d := range days {
		a.Reviews += d.reviews
		if !slices.Contains(a.Years, d.date.Year()) {
			a.Years = append(a.Years, d.date.Year())
		}
		if d.date.Sub(last) == 24*time.Hour {
			run++
		} else {
			run = 1
		}
		last = d.date
		a.LongestStreak = max(a.LongestStreak, run)
		if d.date.Year() == a.Year {
			a.YearReviews += d.reviews
			a.YearDays++
			ms += d.ms
			a.Days = append(a.Days, api.ActivityDay{Date: openapi_types.Date{Time: d.date}, Reviews: d.reviews})
		}
	}
	if !last.IsZero() && today.Sub(last) <= 24*time.Hour {
		a.Streak = run
	}
	a.YearMinutes = int(ms / 60_000)
	if summary, err := s.Q.Summary(ctx, user); err == nil {
		a.Learned = ptrTo(int(summary.Learned))
	}
	return a, nil
}
