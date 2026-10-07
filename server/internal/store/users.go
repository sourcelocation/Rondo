// Package store is the database: queries sqlc generates from db/queries, and a few helpers.
package store

import (
	"context"
	"errors"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/sourcelocation/rondo/server/internal/names"
)

// Ensure makes the users row of an account the first time it's seen, with a username Rondo picks
// (user and eight digits; another when that one is taken).
func Ensure(ctx context.Context, q *Queries, id uuid.UUID) error {
	for range 8 {
		n, err := q.EnsureUser(ctx, EnsureUserParams{ID: id, Username: names.Random(), NameID: uuid.Must(uuid.NewV7())})
		if err != nil || n > 0 {
			return err
		}
		if _, err := q.User(ctx, id); !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
	}
	return errors.New("no free username")
}
