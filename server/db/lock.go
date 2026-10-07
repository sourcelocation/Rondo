package db

import (
	"io/fs"

	"github.com/pressly/goose/v3/lock"
)

func newLocker() (lock.SessionLocker, error) { return lock.NewPostgresSessionLocker() }

func mustSub() fs.FS {
	sub, err := fs.Sub(migrations, "migrations")
	if err != nil {
		panic(err)
	}
	return sub
}

func must[T any](v T, err error) T {
	if err != nil {
		panic(err)
	}
	return v
}
