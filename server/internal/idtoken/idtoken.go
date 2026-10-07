// Package idtoken checks the ID tokens Google's and Apple's own sign-ins give the apps, to link
// those accounts to a Rondo account the way signing in with them would.
package idtoken

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"slices"
	"sync"

	"github.com/coreos/go-oidc/v3/oidc"
)

// Verifier says whose account an ID token is: its subject.
type Verifier interface {
	Subject(ctx context.Context, provider, token, nonce string) (string, error)
}

var (
	ErrProvider = errors.New("that provider isn't set up")
	ErrToken    = errors.New("that token isn't valid")
)

var issuers = map[string]string{"google": "https://accounts.google.com", "apple": "https://appleid.apple.com"}

// Providers verifies tokens against Google's and Apple's published keys, for the apps' client ids.
type Providers struct {
	// ClientIDs are the audiences a provider's tokens may be for (web and the apps).
	ClientIDs map[string][]string

	mu        sync.Mutex
	verifiers map[string]*oidc.IDTokenVerifier
}

func (p *Providers) verifier(ctx context.Context, provider string) (*oidc.IDTokenVerifier, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if v, ok := p.verifiers[provider]; ok {
		return v, nil
	}
	found, err := oidc.NewProvider(ctx, issuers[provider])
	if err != nil {
		return nil, err
	}
	if p.verifiers == nil {
		p.verifiers = map[string]*oidc.IDTokenVerifier{}
	}
	v := found.Verifier(&oidc.Config{SkipClientIDCheck: true})
	p.verifiers[provider] = v
	return v, nil
}

// Subject checks [token]: signed by [provider], for one of its client ids, carrying [nonce] (Apple's
// carry its SHA-256).
func (p *Providers) Subject(ctx context.Context, provider, token, nonce string) (string, error) {
	ids := p.ClientIDs[provider]
	if len(ids) == 0 || issuers[provider] == "" {
		return "", ErrProvider
	}
	v, err := p.verifier(ctx, provider)
	if err != nil {
		return "", err
	}
	t, err := v.Verify(ctx, token)
	if err != nil {
		return "", ErrToken
	}
	if !slices.ContainsFunc(t.Audience, func(a string) bool { return slices.Contains(ids, a) }) {
		return "", ErrToken
	}
	want := nonce
	if provider == "apple" && nonce != "" {
		sum := sha256.Sum256([]byte(nonce))
		want = hex.EncodeToString(sum[:])
	}
	if nonce != "" && t.Nonce != want {
		return "", ErrToken
	}
	return t.Subject, nil
}
