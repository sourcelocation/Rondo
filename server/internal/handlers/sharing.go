package handlers

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/jobs"
	"github.com/sourcelocation/rondo/server/internal/mail"
	"github.com/sourcelocation/rondo/server/internal/pro"
	"github.com/sourcelocation/rondo/server/internal/problem"
	"github.com/sourcelocation/rondo/server/internal/store"
)

func (s *Server) ListShares(ctx context.Context, r api.ListSharesRequestObject) (api.ListSharesResponseObject, error) {
	if _, _, err := s.owned(ctx, r.DeckId); err != nil {
		return nil, err
	}
	rows, err := s.Q.Shares(ctx, r.DeckId)
	if err != nil {
		return nil, err
	}
	out := api.ListShares200JSONResponse{}
	for _, row := range rows {
		name := row.Name
		if name == nil {
			name = &row.Username
		}
		out = append(out, api.Share{UserId: row.UserID, Role: int(row.Role), Name: name, Username: &row.Username})
	}
	return out, nil
}

// editorsAllowed: editors on a lent deck need the owner's Pro.
func (s *Server) editorsAllowed(ctx context.Context, owner uuid.UUID, role int) error {
	if role != 2 {
		return nil
	}
	u, err := s.Q.User(ctx, owner)
	if err != nil {
		return err
	}
	if !pro.Active(u.ProUntil) {
		return fail(http.StatusForbidden, api.ProRequired, "Inviting editors needs Pro.")
	}
	return nil
}

func (s *Server) SetShareRole(ctx context.Context, r api.SetShareRoleRequestObject) (api.SetShareRoleResponseObject, error) {
	id, _, err := s.owned(ctx, r.DeckId)
	if err != nil {
		return nil, err
	}
	if err := s.editorsAllowed(ctx, id.ID, r.Body.Role); err != nil {
		return nil, err
	}
	return api.SetShareRole204Response{}, s.Q.PutShare(ctx, store.PutShareParams{DeckID: r.DeckId, UserID: r.UserId, Role: int16(r.Body.Role)})
}

// RemoveShare: the owner takes a deck back from someone, or someone stops borrowing it.
func (s *Server) RemoveShare(ctx context.Context, r api.RemoveShareRequestObject) (api.RemoveShareResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	if id.ID != r.UserId {
		if _, _, err := s.owned(ctx, r.DeckId); err != nil {
			return nil, err
		}
	}
	if err := s.Q.RemoveShare(ctx, store.RemoveShareParams{DeckID: r.DeckId, UserID: r.UserId}); err != nil {
		return nil, err
	}
	return api.RemoveShare204Response{}, s.refreshListing(ctx, r.DeckId)
}

func inviteOf(i store.Invite) api.Invite {
	return api.Invite{Id: i.ID, Role: int(i.Role), Email: i.Email, ExpiresAt: i.ExpiresAt}
}

func (s *Server) ListInvites(ctx context.Context, r api.ListInvitesRequestObject) (api.ListInvitesResponseObject, error) {
	if _, _, err := s.owned(ctx, r.DeckId); err != nil {
		return nil, err
	}
	rows, err := s.Q.Invites(ctx, r.DeckId)
	if err != nil {
		return nil, err
	}
	out := api.ListInvites200JSONResponse{}
	for _, i := range rows {
		out = append(out, inviteOf(i))
	}
	return out, nil
}

func hashToken(token string) []byte {
	sum := sha256.Sum256([]byte(token))
	return sum[:]
}

// CreateInvite makes a link anyone can use for 30 days, or with an email a single-use invitation
// sent to that address.
func (s *Server) CreateInvite(ctx context.Context, r api.CreateInviteRequestObject) (api.CreateInviteResponseObject, error) {
	id, deck, err := s.owned(ctx, r.DeckId)
	if err != nil {
		return nil, err
	}
	owner, err := s.user(ctx, id.ID)
	if err != nil {
		return nil, err
	}
	if owner.ProfileConfirmedAt == nil {
		return nil, errProfile
	}
	if err := s.social(ctx, id.ID); err != nil {
		return nil, err
	}
	if r.Body.Role < 1 || r.Body.Role > 2 {
		return nil, fail(http.StatusBadRequest, api.Invalid, "A role is 1 (viewer) or 2 (editor).")
	}
	if err := s.editorsAllowed(ctx, id.ID, r.Body.Role); err != nil {
		return nil, err
	}
	secret := make([]byte, 24)
	_, _ = rand.Read(secret)
	token := base64.RawURLEncoding.EncodeToString(secret)
	var email *string
	if r.Body.Email != nil {
		e := strings.ToLower(strings.TrimSpace(string(*r.Body.Email)))
		email = &e
	}
	invite := store.CreateInviteParams{
		ID: uuid.Must(uuid.NewV7()), TokenHash: hashToken(token), DeckID: r.DeckId, Role: int16(r.Body.Role),
		Email: email, CreatedBy: id.ID, ExpiresAt: time.Now().Add(30 * 24 * time.Hour),
	}
	if err := s.Q.CreateInvite(ctx, invite); err != nil {
		return nil, err
	}
	link := s.S.PublicURL + "/i/" + token // the site sends it on to the web app
	out := api.Invite{Id: invite.ID, Role: r.Body.Role, Email: email, ExpiresAt: invite.ExpiresAt}
	if email != nil {
		if err := s.enqueue(ctx, jobs.Mail{Message: mail.Invite(*email, *display(owner), deck.Name, link)}); err != nil {
			return nil, err
		}
	} else {
		out.Url = &link
	}
	return api.CreateInvite201JSONResponse(out), nil
}

func (s *Server) RevokeInvite(ctx context.Context, r api.RevokeInviteRequestObject) (api.RevokeInviteResponseObject, error) {
	invite, err := s.Q.Invite(ctx, r.InviteId)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, errNotFound
	}
	if err != nil {
		return nil, err
	}
	if _, _, err := s.owned(ctx, invite.DeckID); err != nil {
		return nil, err
	}
	return api.RevokeInvite204Response{}, s.Q.DeleteInvite(ctx, r.InviteId)
}

func (s *Server) invitation(ctx context.Context, token string) (store.Invite, store.DeckRow, *string, error) {
	invite, err := s.Q.InviteByHash(ctx, hashToken(token))
	if errors.Is(err, pgx.ErrNoRows) {
		return invite, store.DeckRow{}, nil, fail(http.StatusNotFound, api.NotFound, "This invitation expired or was used.")
	}
	if err != nil {
		return invite, store.DeckRow{}, nil, err
	}
	deck, err := s.Q.Deck(ctx, invite.DeckID)
	if err != nil || deck.DeletedAt != nil || deck.OwnerID == nil {
		return invite, deck, nil, errNotFound
	}
	owner, err := s.Q.User(ctx, *deck.OwnerID)
	if err == nil && (active(owner.RestrictedUntil) || active(owner.BannedUntil)) {
		return invite, deck, nil, fail(http.StatusNotFound, api.NotFound, "This invitation expired or was used.")
	}
	return invite, deck, display(owner), err
}

func (s *Server) PreviewInvitation(ctx context.Context, r api.PreviewInvitationRequestObject) (api.PreviewInvitationResponseObject, error) {
	invite, deck, _, err := s.invitation(ctx, r.Token)
	if err != nil {
		return nil, err
	}
	preview, err := s.publicDeck(ctx, "AND d.id = $4", "", "", 0, deck.ID)
	if err != nil {
		return nil, err
	}
	return api.PreviewInvitation200JSONResponse{Deck: preview, Role: int(invite.Role)}, nil
}

func (s *Server) AcceptInvitation(ctx context.Context, r api.AcceptInvitationRequestObject) (api.AcceptInvitationResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	invite, deck, owner, err := s.invitation(ctx, r.Token)
	if err != nil {
		return nil, err
	}
	if invite.Email != nil && !strings.EqualFold(*invite.Email, id.Email) {
		return nil, fail(http.StatusForbidden, api.Forbidden, "This invitation is for another email address.")
	}
	if _, err := s.user(ctx, id.ID); err != nil {
		return nil, err
	}
	if *deck.OwnerID != id.ID {
		if err := s.Q.AddShare(ctx, store.AddShareParams{DeckID: deck.ID, UserID: id.ID, Role: invite.Role}); err != nil {
			return nil, err
		}
	}
	if invite.Email != nil {
		if err := s.Q.UseInvite(ctx, invite.ID); err != nil {
			return nil, err
		}
	}
	return api.AcceptInvitation200JSONResponse{DeckId: deck.ID, Role: int(invite.Role), OwnerId: deck.OwnerID, OwnerName: owner}, nil
}

// Discover ------------------------------------------------------------------------------------

var nonSlug = regexp.MustCompile(`[^a-z0-9]+`)

func slug(name string) string {
	tail := make([]byte, 3)
	_, _ = rand.Read(tail)
	base := strings.Trim(nonSlug.ReplaceAllString(strings.ToLower(name), "-"), "-")
	if len(base) > 40 {
		base = base[:40]
	}
	if base == "" {
		base = "deck"
	}
	return base + "-" + hex.EncodeToString(tail)
}

func (s *Server) GetPublication(ctx context.Context, r api.GetPublicationRequestObject) (api.GetPublicationResponseObject, error) {
	if _, _, err := s.owned(ctx, r.DeckId); err != nil {
		return nil, err
	}
	p, err := s.Q.Publication(ctx, r.DeckId)
	if errors.Is(err, pgx.ErrNoRows) || (err == nil && !p.Listed) {
		return nil, errNotFound
	}
	if err != nil {
		return nil, err
	}
	removed := p.RemovedAt != nil
	return api.GetPublication200JSONResponse{DeckId: p.DeckID, Slug: p.Slug, Followers: int(p.Followers), Removed: &removed}, nil
}

func (s *Server) PublishDeck(ctx context.Context, r api.PublishDeckRequestObject) (api.PublishDeckResponseObject, error) {
	id, deck, err := s.owned(ctx, r.DeckId)
	if err != nil {
		return nil, err
	}
	if u, err := s.user(ctx, id.ID); err != nil || u.ProfileConfirmedAt == nil {
		return nil, errProfile
	}
	if err := s.social(ctx, id.ID); err != nil {
		return nil, err
	}
	if p, err := s.Q.Publication(ctx, r.DeckId); err == nil && p.RemovedAt != nil {
		return nil, fail(http.StatusForbidden, api.Forbidden, "Moderators took this deck off Discover; it can't go back.")
	}
	if err := s.Q.Publish(ctx, store.PublishParams{DeckID: r.DeckId, Slug: slug(deck.Name)}); err != nil {
		return nil, err
	}
	if err := s.refreshListing(ctx, r.DeckId); err != nil {
		return nil, err
	}
	if u, err := s.Q.User(ctx, id.ID); err == nil {
		if err := s.flagPublishing(ctx, u); err != nil {
			return nil, err
		}
	}
	p, err := s.Q.Publication(ctx, r.DeckId)
	if err != nil {
		return nil, err
	}
	return api.PublishDeck200JSONResponse{DeckId: p.DeckID, Slug: p.Slug, Followers: int(p.Followers)}, nil
}

func (s *Server) UnpublishDeck(ctx context.Context, r api.UnpublishDeckRequestObject) (api.UnpublishDeckResponseObject, error) {
	if _, _, err := s.owned(ctx, r.DeckId); err != nil {
		return nil, err
	}
	return api.UnpublishDeck204Response{}, s.Q.Unpublish(ctx, r.DeckId)
}

const discover = `SELECT p.slug, d.id, d.name, d.description, d.language, d.icon, d.color, coalesce(u.name, u.username), u.username, u.flag,
  (SELECT count(*) FROM shares s WHERE s.deck_id = d.id),
  (WITH RECURSIVE sub AS (SELECT x.id FROM decks x WHERE x.id = d.id UNION SELECT c.id FROM decks c JOIN sub ON c.parent_id = sub.id WHERE c.deleted_at IS NULL)
   SELECT count(*) FROM notes n WHERE n.deck_id IN (SELECT id FROM sub) AND n.deleted_at IS NULL)
FROM decks d JOIN users u ON u.id = d.owner_id LEFT JOIN publications p ON p.deck_id = d.id AND p.listed
WHERE d.deleted_at IS NULL AND ($1 = '' OR d.name ILIKE '%' || $1 || '%') AND ($2 = '' OR d.language = $2)
  AND p.removed_at IS NULL AND (u.restricted_until IS NULL OR u.restricted_until < now())
  AND (u.banned_until IS NULL OR u.banned_until < now()) %s
ORDER BY 11 DESC, p.published_at DESC LIMIT 31 OFFSET $3`

func (s *Server) items(ctx context.Context, where string, args ...any) ([]api.DiscoverItem, error) {
	rows, err := s.Pool.Query(ctx, strings.Replace(discover, "%s", where, 1), args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []api.DiscoverItem
	for rows.Next() {
		var i api.DiscoverItem
		var color *int16
		var followers, notes int64
		if err := rows.Scan(&i.Slug, &i.DeckId, &i.Name, &i.Description, &i.Language, &i.Icon, &color, &i.OwnerName, &i.OwnerUsername, &i.OwnerFlag, &followers, &notes); err != nil {
			return nil, err
		}
		if color != nil {
			c := int(*color)
			i.Color = &c
		}
		i.Followers, i.Notes = int(followers), int(notes)
		out = append(out, i)
	}
	return out, rows.Err()
}

// listing is Discover: one table (publications keeps a copy of each listing), read in the order of
// one of its indexes.
const listing = `SELECT p.slug, p.deck_id, p.name, d.description, p.language, d.icon, d.color, coalesce(u.name, u.username),
  u.username, u.flag, p.followers, p.notes
FROM publications p JOIN decks d ON d.id = p.deck_id JOIN users u ON u.id = p.owner_id
WHERE p.listed AND p.removed_at IS NULL AND NOT p.gone
  AND ($1 = '' OR p.name ILIKE '%%' || $1 || '%%') AND ($2 = '' OR p.language = $2)
  AND (u.restricted_until IS NULL OR u.restricted_until < now()) AND (u.banned_until IS NULL OR u.banned_until < now())
ORDER BY %s LIMIT 31 OFFSET $3`

var orders = map[api.SearchDiscoverParamsSort]string{
	api.Top: "p.followers DESC, p.published_at DESC",
	api.New: "p.published_at DESC",
	api.Hot: "p.hot DESC, p.followers DESC, p.published_at DESC",
}

func (s *Server) SearchDiscover(ctx context.Context, r api.SearchDiscoverRequestObject) (api.SearchDiscoverResponseObject, error) {
	page := 0
	if r.Params.Page != nil {
		page = *r.Params.Page
	}
	sort := api.Top
	if r.Params.Sort != nil {
		sort = *r.Params.Sort
	}
	order, ok := orders[sort]
	if !ok {
		return nil, problem.Invalid("That isn't a way to sort.")
	}
	rows, err := s.Pool.Query(ctx, fmt.Sprintf(listing, order), deref(r.Params.Q), deref(r.Params.Language), page*30)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := []api.DiscoverItem{}
	for rows.Next() {
		var i api.DiscoverItem
		var color *int16
		var followers, notes int32
		if err := rows.Scan(&i.Slug, &i.DeckId, &i.Name, &i.Description, &i.Language, &i.Icon, &color, &i.OwnerName, &i.OwnerUsername,
			&i.OwnerFlag, &followers, &notes); err != nil {
			return nil, err
		}
		if color != nil {
			c := int(*color)
			i.Color = &c
		}
		i.Followers, i.Notes = int(followers), int(notes)
		items = append(items, i)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	more := len(items) > 30
	if more {
		items = items[:30]
	}
	return api.SearchDiscover200JSONResponse{Items: items, More: more}, nil
}

// refreshListing brings Discover's copy of a deck's listing up to date, after it's published,
// followed or unfollowed.
func (s *Server) refreshListing(ctx context.Context, deck uuid.UUID) error {
	return s.Q.RefreshPublications(ctx, &deck)
}

// GetPublicDeck shows a published deck with its sub-decks and a few sample cards.
func (s *Server) GetPublicDeck(ctx context.Context, r api.GetPublicDeckRequestObject) (api.GetPublicDeckResponseObject, error) {
	preview, err := s.publicDeck(ctx, "AND p.slug = $4", "", "", 0, r.Slug)
	if err != nil {
		return nil, err
	}
	return api.GetPublicDeck200JSONResponse(preview), nil
}

// publicDeck is the one deck [items] finds, with its sub-decks and a few sample cards: what
// Discover and invitations both show before anything is borrowed.
func (s *Server) publicDeck(ctx context.Context, where string, args ...any) (api.PublicDeck, error) {
	items, err := s.items(ctx, where, args...)
	if err != nil {
		return api.PublicDeck{}, err
	}
	if len(items) == 0 {
		return api.PublicDeck{}, errNotFound
	}
	item := items[0]
	decks, err := decode[api.Deck](s.Q.Subtree(ctx, item.DeckId))
	if err != nil {
		return api.PublicDeck{}, err
	}
	notes, err := decode[api.Note](s.Q.Samples(ctx, item.DeckId))
	if err != nil {
		return api.PublicDeck{}, err
	}
	var ids []uuid.UUID
	for _, n := range notes {
		ids = append(ids, n.TemplateId)
	}
	templates, err := decode[api.Template](s.Q.TemplatesByID(ctx, ids))
	if err != nil {
		return api.PublicDeck{}, err
	}
	return api.PublicDeck{Item: item, Decks: decks, Samples: api.Rows{Notes: &notes, Templates: &templates}}, nil
}

func decode[T any](rows []string, err error) ([]T, error) {
	if err != nil {
		return nil, err
	}
	out := make([]T, 0, len(rows))
	for _, raw := range rows {
		var v T
		if err := json.Unmarshal([]byte(raw), &v); err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, nil
}

func (s *Server) FollowDeck(ctx context.Context, r api.FollowDeckRequestObject) (api.FollowDeckResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	p, err := s.Q.PublicationBySlug(ctx, r.Slug)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, errNotFound
	}
	if err != nil {
		return nil, err
	}
	deck, err := s.Q.Deck(ctx, p.DeckID)
	if err != nil || deck.OwnerID == nil || deck.DeletedAt != nil {
		return nil, errNotFound
	}
	owner, _ := s.Q.User(ctx, *deck.OwnerID)
	if _, err := s.user(ctx, id.ID); err != nil {
		return nil, err
	}
	if *deck.OwnerID != id.ID {
		if err := s.Q.AddShare(ctx, store.AddShareParams{DeckID: deck.ID, UserID: id.ID, Role: 1}); err != nil {
			return nil, err
		}
		if err := s.refreshListing(ctx, deck.ID); err != nil {
			return nil, err
		}
	}
	return api.FollowDeck200JSONResponse{DeckId: deck.ID, Role: 1, OwnerId: deck.OwnerID, OwnerName: display(owner)}, nil
}
