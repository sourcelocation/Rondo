// Package problem is the one error type the API answers with: a status and an error code from the
// contract. Handlers and the packages they call return it; anything else is an unexpected failure.
package problem

import (
	"net/http"

	"github.com/sourcelocation/rondo/server/internal/api"
)

type Problem struct {
	Status  int
	Code    api.ErrorCode
	Message string
}

func (p *Problem) Error() string { return p.Message }

func New(status int, code api.ErrorCode, message string) error {
	return &Problem{status, code, message}
}

// The refusals more than one package gives.
var (
	SignIn       = New(http.StatusUnauthorized, api.Unauthorized, "Sign in first.")
	SecondFactor = New(http.StatusForbidden, api.SecondFactorRequired, "Enter the code from your authenticator app.")
	NotFound     = New(http.StatusNotFound, api.NotFound, "That doesn't exist.")
	Forbidden    = New(http.StatusForbidden, api.Forbidden, "You can't do that.")
	Invalid      = func(message string) error { return New(http.StatusBadRequest, api.Invalid, message) }
)
