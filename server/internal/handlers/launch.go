package handlers

import (
	"context"
	"net/http"
	"net/netip"
	"strings"
	"sync"
	"time"

	"github.com/hashicorp/golang-lru/v2/expirable"

	"github.com/sourcelocation/rondo/server/internal/api"
)

// The launch list: the site's sign-ups, handed to Listmonk (internal/launch).

// signupsPerHour is how many sign-ups one network may make in the hour after its first.
const signupsPerHour = 3

var (
	signupsMu sync.Mutex
	// signups counts each network's sign-ups; an entry goes an hour after its first.
	signups = expirable.NewLRU[netip.Prefix, *int](100_000, nil, time.Hour)
)

// signupAllowed counts a sign-up from ip, unless its network has made its three this hour.
func signupAllowed(ip netip.Addr) bool {
	network := networkOf(ip)
	signupsMu.Lock()
	defer signupsMu.Unlock()
	n, ok := signups.Get(network)
	if !ok {
		signups.Add(network, new(1))
		return true
	}
	if *n >= signupsPerHour {
		return false
	}
	*n++
	return true
}

// networkOf is what one person can be told apart by: an IPv4 address, or an IPv6 /64, which anyone
// with one can pick addresses from freely.
func networkOf(ip netip.Addr) netip.Prefix {
	if ip.Is6() {
		network, _ := ip.Prefix(64)
		return network
	}
	return netip.PrefixFrom(ip, ip.BitLen())
}

func (s *Server) JoinLaunchList(ctx context.Context, r api.JoinLaunchListRequestObject) (api.JoinLaunchListResponseObject, error) {
	if !signupAllowed(client(ctx).IP) {
		return nil, fail(http.StatusTooManyRequests, api.RateLimited, "That's a lot of sign-ups from here. Try again in an hour.")
	}
	if err := s.Launch.Join(ctx, strings.ToLower(strings.TrimSpace(string(r.Body.Email)))); err != nil {
		return nil, err
	}
	return api.JoinLaunchList202Response{}, nil
}
