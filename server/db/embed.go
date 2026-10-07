// Package db holds the schema and applies it (`rondo migrate`).
package db

import (
	"context"
	"embed"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"
	"github.com/riverqueue/river/riverdriver/riverpgxv5"
	"github.com/riverqueue/river/rivermigrate"
)

//go:embed migrations/*.sql
var migrations embed.FS

// Migrate applies Rondo's schema (goose, under an advisory lock) and River's tables.
func Migrate(ctx context.Context, url string) error {
	pool, err := pgxpool.New(ctx, url)
	if err != nil {
		return err
	}
	defer pool.Close()
	conn := stdlib.OpenDBFromPool(pool)
	defer conn.Close()
	provider, err := goose.NewProvider(goose.DialectPostgres, conn, mustSub(), goose.WithSessionLocker(must(newLocker())))
	if err != nil {
		return err
	}
	if _, err := provider.Up(ctx); err != nil {
		return err
	}
	migrator, err := rivermigrate.New(riverpgxv5.New(pool), nil)
	if err != nil {
		return err
	}
	_, err = migrator.Migrate(ctx, rivermigrate.DirectionUp, nil)
	return err
}
