package ory

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"time"

	"github.com/google/uuid"
)

// ErrNoHydra: agents' sign-in isn't set up here.
var ErrNoHydra = errors.New("hydra isn't configured")

// ConsentRequest is an agent asking for access, as Hydra describes it.
type ConsentRequest struct {
	Subject        string   `json:"subject"`
	RequestedScope []string `json:"requested_scope"`
	Client         struct {
		ClientID   string `json:"client_id"`
		ClientName string `json:"client_name"`
		ClientURI  string `json:"client_uri"`
	} `json:"client"`
}

// Name is the agent's name, or its id when it gave none.
func (r *ConsentRequest) Name() string {
	if r.Client.ClientName != "" {
		return r.Client.ClientName
	}
	return r.Client.ClientID
}

// ConsentSession is access an agent was given.
type ConsentSession struct {
	HandledAt      time.Time      `json:"handled_at"`
	ConsentRequest ConsentRequest `json:"consent_request"`
}

type redirect struct {
	RedirectTo string `json:"redirect_to"`
}

func (c *Client) hydra(path string) (string, error) {
	if c.HydraAdmin == "" {
		return "", ErrNoHydra
	}
	return c.HydraAdmin + "/admin/oauth2/auth/" + path, nil
}

// AcceptLogin tells Hydra who is signing in for an agent; the answer is where the browser goes next.
func (c *Client) AcceptLogin(ctx context.Context, challenge string, subject uuid.UUID) (string, error) {
	u, err := c.hydra("requests/login/accept?login_challenge=" + url.QueryEscape(challenge))
	if err != nil {
		return "", err
	}
	var out redirect
	err = c.call(ctx, http.MethodPut, u, nil, map[string]any{"subject": subject.String(), "remember": true}, &out)
	return out.RedirectTo, err
}

// Consent is the consent request behind [challenge].
func (c *Client) Consent(ctx context.Context, challenge string) (*ConsentRequest, error) {
	u, err := c.hydra("requests/consent?consent_challenge=" + url.QueryEscape(challenge))
	if err != nil {
		return nil, err
	}
	var out ConsentRequest
	err = c.call(ctx, http.MethodGet, u, nil, nil, &out)
	return &out, err
}

// DecideConsent grants what the agent asked for (to [audience]) or refuses it.
func (c *Client) DecideConsent(ctx context.Context, challenge string, r *ConsentRequest, accept bool, audience string) (string, error) {
	action, body := "reject", any(map[string]string{"error": "access_denied", "error_description": "The person said no."})
	if accept {
		action = "accept"
		body = map[string]any{"grant_scope": r.RequestedScope, "grant_access_token_audience": []string{audience}, "remember": true}
	}
	u, err := c.hydra("requests/consent/" + action + "?consent_challenge=" + url.QueryEscape(challenge))
	if err != nil {
		return "", err
	}
	var out redirect
	err = c.call(ctx, http.MethodPut, u, nil, body, &out)
	return out.RedirectTo, err
}

// ConsentSessions lists the agents [subject] gave access to.
func (c *Client) ConsentSessions(ctx context.Context, subject uuid.UUID) ([]ConsentSession, error) {
	if c.HydraAdmin == "" {
		return nil, nil
	}
	u, _ := c.hydra("sessions/consent?subject=" + subject.String())
	var out []ConsentSession
	err := c.call(ctx, http.MethodGet, u, nil, nil, &out)
	return out, err
}

// RevokeConsent takes access back from one agent ([client]), or from every agent when it's empty,
// which also ends their tokens.
func (c *Client) RevokeConsent(ctx context.Context, subject uuid.UUID, client string) error {
	if c.HydraAdmin == "" {
		return nil
	}
	q := url.Values{"subject": {subject.String()}}
	if client == "" {
		q.Set("all", "true")
	} else {
		q.Set("client", client)
	}
	u, _ := c.hydra("sessions/consent?" + q.Encode())
	return c.call(ctx, http.MethodDelete, u, nil, nil, nil)
}
