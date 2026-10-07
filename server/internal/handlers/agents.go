package handlers

import (
	"context"
	"errors"
	"net/http"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/jobs"
	"github.com/sourcelocation/rondo/server/internal/mail"
	"github.com/sourcelocation/rondo/server/internal/ory"
)

// Agents (Hydra): an assistant's OAuth sign-in lands in the web app, which accepts it for whoever is
// signed in there and shows the consent page.

var errNoAgents = fail(http.StatusServiceUnavailable, api.Unavailable, "Agents aren't set up here.")

func hydraErr(err error) error {
	if errors.Is(err, ory.ErrNoHydra) {
		return errNoAgents
	}
	return errNotFound
}

func (s *Server) consent(ctx context.Context, challenge string) (*ory.ConsentRequest, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	c, err := s.Ory.Consent(ctx, challenge)
	if err != nil {
		return nil, hydraErr(err)
	}
	if c.Subject != id.ID.String() {
		return nil, errNotYours
	}
	return c, nil
}

// AcceptLogin tells Hydra who is signing in for an agent: the person this session belongs to.
func (s *Server) AcceptLogin(ctx context.Context, r api.AcceptLoginRequestObject) (api.AcceptLoginResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	next, err := s.Ory.AcceptLogin(ctx, r.Challenge, id.ID)
	if err != nil {
		return nil, hydraErr(err)
	}
	return api.AcceptLogin200JSONResponse{Url: next}, nil
}

func (s *Server) GetConsent(ctx context.Context, r api.GetConsentRequestObject) (api.GetConsentResponseObject, error) {
	c, err := s.consent(ctx, r.Challenge)
	if err != nil {
		return nil, err
	}
	var uri *string
	if c.Client.ClientURI != "" {
		uri = &c.Client.ClientURI
	}
	return api.GetConsent200JSONResponse{ClientId: c.Client.ClientID, ClientName: c.Name(), ClientUri: uri, Scopes: c.RequestedScope}, nil
}

// DecideConsent lets an agent in or not; a person who lets one in is told by email, so an agent
// connected without them doesn't go unnoticed.
func (s *Server) DecideConsent(ctx context.Context, r api.DecideConsentRequestObject) (api.DecideConsentResponseObject, error) {
	c, err := s.consent(ctx, r.Challenge)
	if err != nil {
		return nil, err
	}
	next, err := s.Ory.DecideConsent(ctx, r.Challenge, c, r.Body.Accept, s.S.PublicURL+"/mcp")
	if err != nil {
		return nil, err
	}
	if id, _ := me(ctx); r.Body.Accept && id != nil && id.Email != "" {
		if err := s.enqueue(ctx, jobs.Mail{Message: mail.AgentConnected(id.Email, c.Name())}); err != nil {
			return nil, err
		}
	}
	return api.DecideConsent200JSONResponse{Url: next}, nil
}

func (s *Server) ListAgents(ctx context.Context, _ api.ListAgentsRequestObject) (api.ListAgentsResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	sessions, err := s.Ory.ConsentSessions(ctx, id.ID)
	if err != nil {
		return nil, err
	}
	out := api.ListAgents200JSONResponse{}
	for _, c := range sessions {
		out = append(out, api.Agent{ClientId: c.ConsentRequest.Client.ClientID, ClientName: c.ConsentRequest.Name(), GrantedAt: c.HandledAt})
	}
	return out, nil
}

func (s *Server) RevokeAgent(ctx context.Context, r api.RevokeAgentRequestObject) (api.RevokeAgentResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	if s.Ory.HydraAdmin == "" {
		return nil, errNoAgents
	}
	return api.RevokeAgent204Response{}, s.Ory.RevokeConsent(ctx, id.ID, r.ClientId)
}
