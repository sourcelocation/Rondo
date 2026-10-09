// Package app wires the Go server: configuration, migrations, River, and the HTTP routes.
package app

import (
	"cmp"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/caarlos0/env/v11"
	"github.com/getsentry/sentry-go"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/labstack/echo/v5"
	"github.com/labstack/echo/v5/middleware"
	"github.com/riverqueue/river"
	"github.com/riverqueue/river/riverdriver/riverpgxv5"
	"github.com/sourcelocation/dawl/appstore"
	"github.com/sourcelocation/dawl/googleplay"
	"github.com/sourcelocation/dawl/stripe"
	"github.com/sourcelocation/dawl/subscription"

	"github.com/sourcelocation/rondo/server/db"
	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/blob"
	"github.com/sourcelocation/rondo/server/internal/handlers"
	"github.com/sourcelocation/rondo/server/internal/idtoken"
	"github.com/sourcelocation/rondo/server/internal/jobs"
	"github.com/sourcelocation/rondo/server/internal/launch"
	"github.com/sourcelocation/rondo/server/internal/mail"
	"github.com/sourcelocation/rondo/server/internal/notify"
	"github.com/sourcelocation/rondo/server/internal/ory"
	"github.com/sourcelocation/rondo/server/internal/reminders"
	"github.com/sourcelocation/rondo/server/internal/staff"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// Config comes from RONDO_* environment variables. PublicURL is the one origin Rondo is served from:
// the web app under /app, the servers and Kratos under their paths.
type Config struct {
	Addr         string `env:"RONDO_HTTP_ADDR" envDefault:":23901"`
	DatabaseURL  string `env:"RONDO_DATABASE_URL,required"`
	PublicURL    string `env:"RONDO_PUBLIC_URL" envDefault:"http://localhost:23900"`
	KratosPublic string `env:"RONDO_KRATOS_PUBLIC_URL" envDefault:"http://localhost:23904"`
	KratosAdmin  string `env:"RONDO_KRATOS_ADMIN_URL" envDefault:"http://localhost:23905"`
	HydraAdmin   string `env:"RONDO_HYDRA_ADMIN_URL"`
	HookSecret   string `env:"RONDO_HOOK_SECRET"`
	// EmailKeySecret keys the hashes that find accounts sharing an inbox; the hook secret when empty.
	EmailKeySecret string `env:"RONDO_EMAIL_KEY_SECRET"`
	SentryDSN      string `env:"RONDO_SENTRY_DSN"`
	// OwnerEmail is the owner's account: once that address is verified, its staff powers are all.
	OwnerEmail string `env:"RONDO_OWNER_EMAIL"`
	// GoogleClientIDs and AppleClientIDs are the audiences of the ID tokens Google's and Apple's
	// sign-ins give the web and the apps, for linking those accounts (comma-separated).
	GoogleClientIDs []string `env:"RONDO_GOOGLE_CLIENT_IDS"`
	AppleClientIDs  []string `env:"RONDO_APPLE_CLIENT_IDS"`
	// VAPID keys sign Web Push messages (reminders in browsers); without them, none are sent.
	VAPIDPublicKey  string `env:"RONDO_VAPID_PUBLIC_KEY"`
	VAPIDPrivateKey string `env:"RONDO_VAPID_PRIVATE_KEY"`
	VAPIDSubject    string `env:"RONDO_VAPID_SUBJECT" envDefault:"mailto:support@matthewsource.com"`
	// StaffPasskey "off" lets local development use staff tools without a passkey; only on localhost.
	StaffPasskey string `env:"RONDO_STAFF_PASSKEY" envDefault:"on"`

	MediaDir    string `env:"RONDO_MEDIA_DIR"`
	S3Endpoint  string `env:"RONDO_S3_ENDPOINT"`
	S3Region    string `env:"RONDO_S3_REGION" envDefault:"auto"`
	S3Bucket    string `env:"RONDO_S3_BUCKET"`
	S3AccessKey string `env:"RONDO_S3_ACCESS_KEY"`
	S3SecretKey string `env:"RONDO_S3_SECRET_KEY"`
	FreeMediaMB int64  `env:"RONDO_FREE_MEDIA_MB" envDefault:"200"`
	ProMediaMB  int64  `env:"RONDO_PRO_MEDIA_MB" envDefault:"5000"`

	SMTPHost     string `env:"RONDO_SMTP_HOST" envDefault:"localhost"`
	SMTPPort     int    `env:"RONDO_SMTP_PORT" envDefault:"23909"`
	SMTPUser     string `env:"RONDO_SMTP_USER"`
	SMTPPassword string `env:"RONDO_SMTP_PASSWORD"`
	SMTPTLS      string `env:"RONDO_SMTP_TLS" envDefault:"none"`
	MailFrom     string `env:"RONDO_MAIL_FROM" envDefault:"Rondo <hello@rondo.localhost>"`
	BrandDir     string `env:"RONDO_BRAND_DIR" envDefault:"../brand/dist"`

	// Listmonk keeps the launch list (internal/launch): its address, its API user as "user:token", and
	// the list's ID there. Without them, the list isn't open.
	ListmonkURL   string `env:"RONDO_LISTMONK_URL"`
	ListmonkToken string `env:"RONDO_LISTMONK_TOKEN"`
	ListmonkList  int    `env:"RONDO_LISTMONK_LIST"`

	StripeKey          string `env:"RONDO_STRIPE_KEY"`
	StripeWebhook      string `env:"RONDO_STRIPE_WEBHOOK_SECRET"`
	StripeMonthly      string `env:"RONDO_STRIPE_PRICE_MONTHLY"`
	StripeYearly       string `env:"RONDO_STRIPE_PRICE_YEARLY"`
	AppStoreBundle     string `env:"RONDO_APPSTORE_BUNDLE_ID" envDefault:"com.matthewsource.rondo"`
	AppStoreIssuer     string `env:"RONDO_APPSTORE_ISSUER_ID"`
	AppStoreKeyID      string `env:"RONDO_APPSTORE_KEY_ID"`
	AppStoreKey        string `env:"RONDO_APPSTORE_KEY"`
	AppStoreAppleID    int64  `env:"RONDO_APPSTORE_APPLE_ID"`
	PlayPackage        string `env:"RONDO_PLAY_PACKAGE" envDefault:"com.matthewsource.rondo"`
	PlayServiceAccount string `env:"RONDO_PLAY_SERVICE_ACCOUNT"`
	PlayPubSubAudience string `env:"RONDO_PLAY_PUBSUB_AUDIENCE"`
	PlayPubSubAccount  string `env:"RONDO_PLAY_PUBSUB_ACCOUNT"`
}

func Load() (Config, error) { return env.ParseAs[Config]() }

// Migrate applies the schema and River's tables.
func Migrate(ctx context.Context, cfg Config) error { return db.Migrate(ctx, cfg.DatabaseURL) }

// Serve runs the HTTP API and River's workers until ctx ends.
func Serve(ctx context.Context, cfg Config, log *slog.Logger) error {
	if cfg.SentryDSN != "" {
		if err := sentry.Init(sentry.ClientOptions{Dsn: cfg.SentryDSN}); err != nil {
			return err
		}
	}
	pool, err := pgxpool.New(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer pool.Close()

	var blobs blob.Store = blob.Folder{Dir: cfg.MediaDir, Base: cfg.PublicURL}
	if cfg.S3Bucket != "" {
		blobs = blob.NewS3(cfg.S3Endpoint, cfg.S3Region, cfg.S3Bucket, cfg.S3AccessKey, cfg.S3SecretKey)
	} else if cfg.MediaDir == "" {
		return errors.New("set RONDO_MEDIA_DIR or RONDO_S3_BUCKET")
	}
	mailer := mail.New(mail.Config{Host: cfg.SMTPHost, Port: cfg.SMTPPort, User: cfg.SMTPUser, Password: cfg.SMTPPassword, TLS: cfg.SMTPTLS, From: cfg.MailFrom, BrandDir: cfg.BrandDir})
	httpClient := &http.Client{Timeout: 15 * time.Second}
	oryClient := &ory.Client{KratosPublic: cfg.KratosPublic, KratosAdmin: cfg.KratosAdmin, HydraAdmin: cfg.HydraAdmin, HTTP: httpClient}

	workers := river.NewWorkers()
	river.AddWorker(workers, &jobs.MailWorker{Mailer: mailer})
	customerEmails := &jobs.CustomerEmailWorker{}
	river.AddWorker(workers, customerEmails)
	river.AddWorker(workers, &notify.EmailWorker{Ory: oryClient, Mailer: mailer, PublicURL: cfg.PublicURL})
	staffService := &staff.Service{Pool: pool, Ory: oryClient, Log: log, OwnerEmail: cfg.OwnerEmail}
	river.AddWorker(workers, &staff.ReconcileWorker{Staff: staffService})
	river.AddWorker(workers, &staff.SpikesWorker{Staff: staffService})
	river.AddWorker(workers, &jobs.CleanupWorker{Pool: pool})
	river.AddWorker(workers, &jobs.RefreshDiscoverWorker{Pool: pool})
	resets := &jobs.SecondFactorResetWorker{Pool: pool, Ory: oryClient}
	river.AddWorker(workers, resets)
	pusher := &notify.Pusher{PublicKey: cfg.VAPIDPublicKey, PrivateKey: cfg.VAPIDPrivateKey, Subject: cfg.VAPIDSubject, HTTP: httpClient}
	river.AddWorker(workers, &notify.PushWorker{Pool: pool, Pusher: pusher})
	reminding := &reminders.Worker{Pool: pool, Pusher: pusher, Ory: oryClient, PublicURL: cfg.PublicURL}
	river.AddWorker(workers, reminding)
	river.AddWorker(workers, &jobs.ExportWorker{Pool: pool, Blobs: blobs, Mailer: mailer})
	river.AddWorker(workers, &jobs.MediaGCWorker{Pool: pool, Blobs: blobs})
	queue, err := river.NewClient(riverpgxv5.New(pool), &river.Config{
		Queues:  map[string]river.QueueConfig{river.QueueDefault: {MaxWorkers: 8}},
		Workers: workers,
		PeriodicJobs: []*river.PeriodicJob{
			river.NewPeriodicJob(river.PeriodicInterval(24*time.Hour),
				func() (river.JobArgs, *river.InsertOpts) { return jobs.MediaGC{}, nil }, &river.PeriodicJobOpts{RunOnStart: true}),
			river.NewPeriodicJob(river.PeriodicInterval(24*time.Hour),
				func() (river.JobArgs, *river.InsertOpts) { return jobs.Cleanup{}, nil }, &river.PeriodicJobOpts{RunOnStart: true}),
			river.NewPeriodicJob(river.PeriodicInterval(6*time.Hour),
				func() (river.JobArgs, *river.InsertOpts) { return staff.Spikes{}, nil }, &river.PeriodicJobOpts{RunOnStart: true}),
			river.NewPeriodicJob(river.PeriodicInterval(10*time.Minute),
				func() (river.JobArgs, *river.InsertOpts) { return jobs.RefreshDiscover{}, nil }, &river.PeriodicJobOpts{RunOnStart: true}),
			river.NewPeriodicJob(river.PeriodicInterval(reminders.Every),
				func() (river.JobArgs, *river.InsertOpts) { return reminders.Tick{}, nil }, nil),
		},
		Logger: log,
	})
	if err != nil {
		return err
	}
	sender := &notify.Sender{Jobs: queue}
	resets.Jobs = queue
	reminding.Jobs, reminding.Notify = queue, sender
	skipPasskey := cfg.StaffPasskey == "off" && strings.HasPrefix(cfg.PublicURL, "http://localhost")
	if skipPasskey {
		log.Warn("staff tools work without a passkey (RONDO_STAFF_PASSKEY=off): local development only")
	}
	staffService.Notify, staffService.Jobs, staffService.SkipPasskey = sender, queue, skipPasskey
	if err := staffService.ResolveOwner(ctx); err != nil {
		log.Warn("the owner isn't known yet", "err", err)
	}
	h := &handlers.Server{
		Q: store.New(pool), Pool: pool, Blobs: blobs, Jobs: queue, Ory: oryClient, Staff: staffService, Notify: sender,
		Tokens: &idtoken.Providers{ClientIDs: map[string][]string{"google": cfg.GoogleClientIDs, "apple": cfg.AppleClientIDs}},
		Pusher: pusher,
		Launch: &launch.List{URL: cfg.ListmonkURL, Token: cfg.ListmonkToken, ID: cfg.ListmonkList, HTTP: httpClient},
		S: handlers.Settings{
			PublicURL: cfg.PublicURL, HookSecret: cfg.HookSecret, EmailKeySecret: cmp.Or(cfg.EmailKeySecret, cfg.HookSecret),
			FreeMediaBytes: cfg.FreeMediaMB << 20, ProMediaBytes: cfg.ProMediaMB << 20,
			StripePrices: map[string]string{"monthly": cfg.StripeMonthly, "yearly": cfg.StripeYearly},
		},
	}
	if err := stores(ctx, cfg, h); err != nil {
		return err
	}
	// The stores that are set up, by dawl's names for them.
	gateways := map[subscription.Provider]subscription.Gateway{}
	if h.Stripe != nil {
		gateways[subscription.Stripe] = h.Stripe
		customerEmails.Stripe = h.Stripe
	}
	if h.AppStore != nil {
		gateways[subscription.AppStore] = h.AppStore
	}
	if h.Play != nil {
		gateways[subscription.GooglePlay] = h.Play
	}
	staffService.Renewers = map[subscription.Provider]subscription.Renewer{}
	for provider, gw := range gateways {
		if renewer, ok := gw.(subscription.Renewer); ok {
			staffService.Renewers[provider] = renewer
		}
	}
	// Workers start once everything they use is wired. A signal doesn't cancel running jobs: the
	// drain below lets them finish.
	if err := queue.Start(context.WithoutCancel(ctx)); err != nil {
		return err
	}

	e := echo.New()
	e.HTTPErrorHandler = problems(log)
	e.Use(middleware.Recover(), middleware.BodyLimit(12<<20), protocol, func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c *echo.Context) error {
			c.SetRequest(handlers.WithClient(handlers.WithHookSecret(h.Identify(c.Request()))))
			return next(c)
		}
	})
	api.RegisterHandlers(e, api.NewStrictHandler(h, nil))
	e.GET("/healthz", func(c *echo.Context) error { return c.String(http.StatusOK, "ok") })
	if cfg.MediaDir != "" {
		e.GET("/blobs/*", echo.WrapHandler(blob.ServeFolder(cfg.MediaDir)))
	}
	for provider, gw := range gateways {
		path := "/api/webhooks/" + strings.ReplaceAll(string(provider), "_", "-")
		e.POST(path, echo.WrapHandler(subscription.Webhook(gw, h.Save)))
	}

	server := &http.Server{Addr: cfg.Addr, Handler: e, ReadHeaderTimeout: 10 * time.Second}
	served := make(chan error, 1)
	go func() { served <- server.ListenAndServe() }()
	log.Info("serving", "addr", cfg.Addr)
	select {
	case err := <-served:
		_ = queue.StopAndCancel(context.WithoutCancel(ctx))
		return err
	case <-ctx.Done():
	}

	// The drain, within the pod's grace period: requests in flight finish, then running jobs. Jobs
	// still running at the deadline are cancelled, and River runs them again later.
	draining, cancel := context.WithTimeout(context.WithoutCancel(ctx), 20*time.Second)
	defer cancel()
	log.Info("draining")
	err = server.Shutdown(draining)
	if queue.Stop(draining) != nil {
		_ = queue.StopAndCancel(context.WithoutCancel(ctx))
	}
	return err
}

// stores connects the payment providers that are configured.
func stores(ctx context.Context, cfg Config, h *handlers.Server) error {
	if cfg.StripeKey != "" {
		h.Stripe = stripe.New(stripe.Config{
			SecretKey: cfg.StripeKey, WebhookSecret: cfg.StripeWebhook, AccountMetadataKey: "rondo_account", IdempotencyPrefix: "rondo-",
			SuccessURL: cfg.PublicURL + "/app/settings/plan?paid=1", CancelURL: cfg.PublicURL + "/app/settings/plan", PortalReturnURL: cfg.PublicURL + "/app/settings/plan",
		})
	}
	if cfg.AppStoreKey != "" {
		gw, err := appstore.New(appstore.Config{BundleID: cfg.AppStoreBundle, IssuerID: cfg.AppStoreIssuer, KeyID: cfg.AppStoreKeyID, Key: cfg.AppStoreKey, AppAppleID: cfg.AppStoreAppleID}, nil)
		if err != nil {
			return fmt.Errorf("app store: %w", err)
		}
		h.AppStore = gw
	}
	if cfg.PlayServiceAccount != "" {
		gw, err := googleplay.New(ctx, googleplay.Config{PackageName: cfg.PlayPackage, ServiceAccountKey: cfg.PlayServiceAccount, PubSubAudience: cfg.PlayPubSubAudience, PubSubAccount: cfg.PlayPubSubAccount}, nil)
		if err != nil {
			return fmt.Errorf("google play: %w", err)
		}
		h.Play = gw
	}
	return nil
}

// protocol refuses clients older than the API (they send Rondo-Protocol).
func protocol(next echo.HandlerFunc) echo.HandlerFunc {
	return func(c *echo.Context) error {
		if v := c.Request().Header.Get("Rondo-Protocol"); v != "" && v != "1" {
			return &handlers.Problem{Status: http.StatusUpgradeRequired, Code: api.ClientOutdated, Message: "Update the app to keep syncing."}
		}
		return next(c)
	}
}

// problems answers every error as the spec's Problem.
func problems(log *slog.Logger) echo.HTTPErrorHandler {
	return func(c *echo.Context, err error) {
		var p *handlers.Problem
		var he echo.HTTPStatusCoder
		switch {
		case errors.As(err, &p):
		case errors.As(err, &he):
			code := api.Invalid
			if he.StatusCode() == http.StatusNotFound {
				code = api.NotFound
			}
			p = &handlers.Problem{Status: he.StatusCode(), Code: code, Message: http.StatusText(he.StatusCode())}
		case errors.Is(err, pgx.ErrNoRows):
			p = &handlers.Problem{Status: http.StatusNotFound, Code: api.NotFound, Message: "That doesn't exist."}
		default:
			log.Error("request failed", "path", c.Request().URL.Path, "err", err)
			sentry.CaptureException(err)
			p = &handlers.Problem{Status: http.StatusInternalServerError, Code: api.Internal, Message: "Something went wrong."}
		}
		_ = c.JSON(p.Status, api.Problem{Code: p.Code, Message: p.Message})
	}
}
