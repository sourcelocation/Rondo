// Package ory talks to Kratos (who is signed in, and its admin API for staff and account changes)
// and to Hydra's admin API (agents' sign-ins).
package ory

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/google/uuid"
)

type Client struct {
	KratosPublic, KratosAdmin, HydraAdmin string
	HTTP                                  *http.Client
}

// ErrSecondFactor: the session is real, but its person turned on a second factor this session hasn't
// completed yet.
var ErrSecondFactor = errors.New("second factor required")

// ErrConflict: Kratos refused a change because another identity already has that value.
var ErrConflict = errors.New("conflict")

// Method is one way a session was signed in (code, passkey, oidc, totp, lookup_secret).
type Method struct {
	Method      string    `json:"method"`
	CompletedAt time.Time `json:"completed_at"`
}

// Session is who a request's Kratos session belongs to, and how it was signed in.
type Session struct {
	ID              uuid.UUID
	Email           string
	EmailVerified   bool
	AuthenticatedAt time.Time
	Methods         []Method
}

// Passkey says whether this session signed in with a passkey, within the last [within] when that's
// non-zero.
func (s *Session) Passkey(within time.Duration) bool {
	for _, m := range s.Methods {
		if m.Method == "passkey" && (within == 0 || time.Since(m.CompletedAt) <= within) {
			return true
		}
	}
	return false
}

type whoami struct {
	Active          bool      `json:"active"`
	AuthenticatedAt time.Time `json:"authenticated_at"`
	Methods         []Method  `json:"authentication_methods"`
	Identity        identity  `json:"identity"`
}

type identity struct {
	ID        uuid.UUID `json:"id"`
	State     string    `json:"state"`
	CreatedAt time.Time `json:"created_at"`
	Traits    struct {
		Email string `json:"email"`
	} `json:"traits"`
	Addresses []struct {
		Value    string `json:"value"`
		Verified bool   `json:"verified"`
	} `json:"verifiable_addresses"`
	Credentials map[string]struct {
		Identifiers []string `json:"identifiers"`
		Config      struct {
			Providers []Provider `json:"providers"`
		} `json:"config"`
	} `json:"credentials"`
}

func (i *identity) verified(email string) bool {
	for _, a := range i.Addresses {
		if strings.EqualFold(a.Value, email) && a.Verified {
			return true
		}
	}
	return false
}

// Whoami resolves a session token: nil without an error when it isn't a session.
func (c *Client) Whoami(ctx context.Context, token string) (*Session, error) {
	var body whoami
	err := c.call(ctx, http.MethodGet, c.KratosPublic+"/sessions/whoami", map[string]string{"X-Session-Token": token}, nil, &body)
	var status *StatusError
	switch {
	case errors.As(err, &status) && status.Code == http.StatusForbidden && status.ID == "session_aal2_required":
		return nil, ErrSecondFactor
	case errors.As(err, &status) && status.Code < 500:
		return nil, nil
	case err != nil:
		return nil, err
	case !body.Active:
		return nil, nil
	}
	i := body.Identity
	return &Session{
		ID: i.ID, Email: i.Traits.Email, EmailVerified: i.verified(i.Traits.Email),
		AuthenticatedAt: body.AuthenticatedAt, Methods: body.Methods,
	}, nil
}

// Identity is a Kratos identity as staff and account changes see it.
type Identity struct {
	ID        uuid.UUID
	Email     string
	Verified  bool
	Active    bool
	CreatedAt time.Time
	// Ways to sign in: code, passkey, totp, lookup_secret, oidc.
	Methods   []string
	Providers []Provider
}

// Provider is a linked Google or Apple account.
type Provider struct {
	Provider string `json:"provider"`
	Subject  string `json:"subject"`
}

func (i *identity) public() *Identity {
	out := &Identity{ID: i.ID, Email: i.Traits.Email, Verified: i.verified(i.Traits.Email), Active: i.State != "inactive", CreatedAt: i.CreatedAt}
	for kind, cred := range i.Credentials {
		if len(cred.Identifiers) == 0 && kind != "totp" && kind != "lookup_secret" {
			continue
		}
		out.Methods = append(out.Methods, kind)
		if kind == "oidc" {
			out.Providers = cred.Config.Providers
		}
	}
	return out
}

// Identity reads one identity with its credentials, or nil when there's none.
func (c *Client) Identity(ctx context.Context, id uuid.UUID) (*Identity, error) {
	var i identity
	err := c.call(ctx, http.MethodGet, c.KratosAdmin+"/admin/identities/"+id.String()+"?include_credential=oidc", nil, nil, &i)
	if isStatus(err, http.StatusNotFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return i.public(), nil
}

// FindByEmail is the identity signing in with [email], or nil.
func (c *Client) FindByEmail(ctx context.Context, email string) (*Identity, error) {
	var found []identity
	q := url.Values{"credentials_identifier": {strings.ToLower(strings.TrimSpace(email))}}
	if err := c.call(ctx, http.MethodGet, c.KratosAdmin+"/admin/identities?"+q.Encode(), nil, nil, &found); err != nil {
		return nil, err
	}
	if len(found) == 0 {
		return nil, nil
	}
	return c.Identity(ctx, found[0].ID)
}

type patch struct {
	Op    string `json:"op"`
	Path  string `json:"path"`
	Value any    `json:"value"`
}

func (c *Client) patch(ctx context.Context, id uuid.UUID, ops ...patch) error {
	err := c.call(ctx, http.MethodPatch, c.KratosAdmin+"/admin/identities/"+id.String(), nil, ops, nil)
	if isStatus(err, http.StatusConflict) {
		return ErrConflict
	}
	return err
}

// SetActive turns signing in on or off. Turning it off also ends every session.
func (c *Client) SetActive(ctx context.Context, id uuid.UUID, active bool) error {
	state := "active"
	if !active {
		state = "inactive"
	}
	if err := c.patch(ctx, id, patch{"replace", "/state", state}); err != nil {
		return err
	}
	if active {
		return nil
	}
	return c.EndSessions(ctx, id)
}

// EndSessions signs the identity out everywhere.
func (c *Client) EndSessions(ctx context.Context, id uuid.UUID) error {
	err := c.call(ctx, http.MethodDelete, c.KratosAdmin+"/admin/identities/"+id.String()+"/sessions", nil, nil, nil)
	if isStatus(err, http.StatusNotFound) {
		return nil
	}
	return err
}

// SetEmail moves the identity to [email], which its person has just proved is theirs.
func (c *Client) SetEmail(ctx context.Context, id uuid.UUID, email string) error {
	if err := c.patch(ctx, id, patch{"replace", "/traits/email", email}); err != nil {
		return err
	}
	return c.patch(ctx, id, patch{"replace", "/verifiable_addresses/0/verified", true}, patch{"replace", "/verifiable_addresses/0/status", "completed"})
}

// SetProviders replaces the linked Google and Apple accounts.
func (c *Client) SetProviders(ctx context.Context, id uuid.UUID, providers []Provider) error {
	ids := make([]string, 0, len(providers))
	for _, p := range providers {
		ids = append(ids, p.Provider+":"+p.Subject)
	}
	cred := map[string]any{"type": "oidc", "identifiers": ids, "config": map[string]any{"providers": providers}}
	return c.patch(ctx, id, patch{"add", "/credentials/oidc", cred})
}

// DeleteCredential removes a way to sign in: totp, lookup_secret, or (with an identifier) one linked
// account.
func (c *Client) DeleteCredential(ctx context.Context, id uuid.UUID, kind, identifier string) error {
	u := c.KratosAdmin + "/admin/identities/" + id.String() + "/credentials/" + kind
	if identifier != "" {
		u += "?identifier=" + url.QueryEscape(identifier)
	}
	err := c.call(ctx, http.MethodDelete, u, nil, nil, nil)
	if isStatus(err, http.StatusNotFound) {
		return nil
	}
	return err
}

// Delete removes the identity for good.
func (c *Client) Delete(ctx context.Context, id uuid.UUID) error {
	return c.call(ctx, http.MethodDelete, c.KratosAdmin+"/admin/identities/"+id.String(), nil, nil, nil)
}

// StatusError is an answer from Kratos or Hydra that wasn't a success.
type StatusError struct {
	Method, URL string
	Code        int
	ID          string
}

func (e *StatusError) Error() string {
	return fmt.Sprintf("%s %s: HTTP %d %s", e.Method, e.URL, e.Code, e.ID)
}

func isStatus(err error, code int) bool {
	var s *StatusError
	return errors.As(err, &s) && s.Code == code
}

// call sends JSON and decodes the answer.
func (c *Client) call(ctx context.Context, method, u string, headers map[string]string, in, out any) error {
	var body io.Reader
	if in != nil {
		raw, err := json.Marshal(in)
		if err != nil {
			return err
		}
		body = strings.NewReader(string(raw))
	}
	req, err := http.NewRequestWithContext(ctx, method, u, body)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	res, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode >= 300 {
		var problem struct {
			Error struct {
				ID string `json:"id"`
			} `json:"error"`
		}
		_ = json.NewDecoder(io.LimitReader(res.Body, 1<<16)).Decode(&problem)
		return &StatusError{method, strings.SplitN(u, "?", 2)[0], res.StatusCode, problem.Error.ID}
	}
	if out == nil {
		return nil
	}
	return json.NewDecoder(io.LimitReader(res.Body, 4<<20)).Decode(out)
}
