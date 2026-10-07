// Command rondo is Rondo's Go server.
//
//	rondo serve      the API and background jobs
//	rondo migrate    apply database migrations, then exit
//	rondo --version
package main

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	_ "time/tzdata"

	"github.com/sourcelocation/rondo/server/internal/app"
)

var version = "dev"

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: rondo serve | migrate | --version")
		os.Exit(2)
	}
	if os.Args[1] == "--version" {
		fmt.Println(version)
		return
	}
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	if err := run(os.Args[1], log); err != nil {
		log.Error(os.Args[1], "err", err)
		os.Exit(1)
	}
}

func run(command string, log *slog.Logger) error {
	cfg, err := app.Load()
	if err != nil {
		return fmt.Errorf("configuration: %w", err)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	switch command {
	case "serve":
		return app.Serve(ctx, cfg, log)
	case "migrate":
		return app.Migrate(ctx, cfg)
	default:
		return fmt.Errorf("unknown command %q", command)
	}
}
