// Package launch is the launch list: the addresses that get one email on launch day. Listmonk keeps
// them, emails each a link to confirm (the list is double opt-in) and sends the launch campaign; Rondo
// only hands it the site's sign-ups.
package launch

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/problem"
)

var (
	errClosed  = problem.New(http.StatusServiceUnavailable, api.Unavailable, "The launch list isn't open yet.")
	errAddress = problem.Invalid("That address can't go on the list.")
)

// List is the launch list in Listmonk.
type List struct {
	// URL is Listmonk's address: inside the cluster in production.
	URL string
	// Token is its API user and that user's token, "user:token".
	Token string
	// ID is the list's ID in Listmonk.
	ID   int
	HTTP *http.Client
}

// Join puts an address on the list, unconfirmed, which makes Listmonk send the link to confirm it. An
// address Listmonk has already (waiting, confirmed or gone) is left as it is and sent nothing.
func (l *List) Join(ctx context.Context, email string) error {
	if l == nil || l.URL == "" || l.Token == "" || l.ID == 0 {
		return errClosed
	}
	body, err := json.Marshal(subscriber{Email: email, Status: "enabled", Lists: []int{l.ID}})
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, l.URL+"/api/subscribers", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "token "+l.Token)
	req.Header.Set("Content-Type", "application/json")
	res, err := l.HTTP.Do(req)
	if err != nil {
		return fmt.Errorf("listmonk: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	switch res.StatusCode {
	case http.StatusOK, http.StatusConflict:
		return nil
	case http.StatusBadRequest: // an address it won't take, or a domain on its blocklist
		return errAddress
	default:
		reply, _ := io.ReadAll(io.LimitReader(res.Body, 512))
		return fmt.Errorf("listmonk: %s: %s", res.Status, bytes.TrimSpace(reply))
	}
}

// subscriber is Listmonk's new subscriber. With no name, Listmonk makes one from the address.
type subscriber struct {
	Email  string `json:"email"`
	Status string `json:"status"`
	Lists  []int  `json:"lists"`
}
