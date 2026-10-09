# Architecture

A map of Rondo for people who change it: what runs where, who owns what, and the rules that hold
everywhere. Using Rondo and its API is covered in the [docs](https://rondo.matthewsource.com/docs/).
This file is where decisions live: when one changes, it changes here too.

## The shape of it

- **Local-first.** Every device keeps everything its account can see in its own SQLite database,
  and reads and writes only that. Sync runs in the background. Without an account, everything works
  except syncing and sharing.
- **One set of rules.** What a write may do is decided by Kotlin in `app/core`, which runs on
  devices before they write and on the sync server before it stores. No rule exists twice.
- **Two servers, one PostgreSQL.** The Go server (`server/`) does everything that involves people
  or money. The Kotlin sync server (`app/sync`) moves rows between devices and Postgres and hosts
  the MCP server. Kratos signs people in, headless; Hydra is the OAuth server for AI assistants.
- **Screens in Kotlin, UIs per platform.** `app/client` holds every screen's state and actions. Each
  UI (React today, SwiftUI and Compose next, following the web) draws that state, calls those
  actions, and decides nothing itself.

## Principles

- **What Rondo leaves out**, on purpose: a search query language, flag colours, an inbox, comments,
  video, leech handling, per-learner study settings, pins, archiving, and daily time budgets, easy
  days and load balancing. New ideas start from this list. (A smart deck's limits are the
  learner's own row, not a setting on someone else's deck.)
- **Libraries over infrastructure.** No hand-built code generators or preprocessors, and no
  committed shell scripts. An interface exists only when it has two real implementations.
- **The Kotlin is used directly.** Every client runs `:core` and `:client` as Kotlin Multiplatform
  builds them, never through a bridge or a translation. A platform that can't is a reason to change
  the architecture, not to add a layer.

## One hostname

Everything is served from `rondo.matthewsource.com`, routed by path: `/` the landing page, `/docs`,
`/app` the web app, `/api` and `/media` the Go server, `/sync` and `/mcp` the sync server, `/auth`
Kratos, and `/oauth2/…` Hydra. One origin means no CORS and no cookies, and one URL for assistants.
Locally, the web app's dev server reproduces it on `http://localhost:23900` by proxying each path,
and on a server of someone else's, Caddy does (`server/self-host/compose.yaml`). So a new public
path needs a rule in the ops repository's ingress, a proxy entry in `vite.config.ts` and a route in
that Caddyfile. Rondo never gets subdomains: Cloudflare's free certificate covers one level.

## Repository

Four standalone projects, each with its own toolchain, which meet only through contract files:

- **`server/api/rondo.yaml`** (OpenAPI) is the API. It generates the Go server's interfaces, the
  Kotlin models and client, and the docs' API reference. An API change starts here.
- **`server/db/migrations`** is the schema of the one database, applied by the Go server.
- **`brand/dist`** is everything the brand produces, committed: CSS, tokens and native resources.

`app/` is one Gradle build: `:core` (rules, markup, templates, FSRS, ids; no I/O), `:client`
(everything a device does, and every screen), `:mcp`, `:cli`, `:sync` and `platforms/web`.

## Data

- **The pile**: decks, templates and notes. Each row has one owner (`owner_id`), which never
  changes, and a clock (`v`); the newest version of a whole row wins.
- **The learner**: append-only `events`, one `settings` row, and their `smart_decks` (rows with an
  owner and a clock, like the pile's, but never lent). Two devices' answers never conflict. A
  card's state is never stored on the server; it is folded from its events.
- **People, access and money**: everything else, written only by the Go server.

Synced tables have no foreign keys: pushes arrive in any order, and `:core` checks references.
Ids are UUIDv7, `v` is a hybrid logical clock that fits JavaScript's numbers, and siblings are
ordered by fractional keys. Deleted rows are purged after 30 days, but what others borrow stays,
authorless and read-only. Media are named by their SHA-256 and never change.

## The parts

| Part | Where | What to know |
| --- | --- | --- |
| Rules | `:core` | `Rules` takes the stored row, the new one and an `Access` (who writes, the deck tree, what is lent, which owners have Pro) and returns an error code or null. Devices call it before writing, the sync server before storing. |
| Sync | `:client`, `:sync` | Upload media, push, then pull each stream (the account's own rows, one per lent deck) from the last number seen. Every request carries `Rondo-Protocol: 1`; an app too old gets `426`. A note pushed without `tags` (an app from before them) keeps the stored ones. |
| Content | `:core` | A field is one markup string (clozes, math, ruby). Templates are blocks laid out as `Piece`s each UI draws. Fields *in the deck's language* are read aloud. A note's tags are Anki's form, `::` nesting them; `Tags` makes and checks them, and a tag finds the ones under it. |
| Studying | `:core`, `:client` | FSRS-6 over events, fitted to each learner on the device. Days start at 4 am in the learner's time zone. A session draws from `Scope`s: a deck with its sub-decks, or a filter (a smart deck, Browse's "Study these"). Wherever it's studied, a card is scheduled by its own deck and counts toward that deck's day, and a locked level gives no new cards. Once a session is done, reviews not due yet can follow, weakest first. |
| Smart decks | `:core`, `:client` | Browse's filters kept (a deck stands for its sub-decks, a tag for the tags under it), studied within their own daily limits; new cards come in deck order. The learner's alone, never lent or published. Browse is where their rules change. |
| Levels | `:core` | Sub-decks of a gated deck open in order, by progress or by hand; opening is an event. |
| Anki import | `:client` | `.apkg` and `.colpkg` read on the device, with media, history and tags (Anki's `marked` becomes a mark, `leech` goes). A note type can stay Anki's HTML, read-only (1:1); its notes' tags still change. |
| Accounts | `:client`, `internal/ory` | Kratos's API flows: email code, passkey, Apple or Google. Second step, passkeys, linked accounts, email change and devices in Settings › Security. |
| Profiles | `internal/names` | A username (needed to share or publish), a display name and a flag; a public page with activity. |
| Sharing | Go server | Decks are lent as viewer or editor (editing needs the owner's Pro), by invitation or link; following is borrowing as a viewer. |
| Discover | Go server | Published decks, read from `publications`, a copy refreshed on a schedule. Sorts are Top, New and Hot. |
| Billing | Go server, [dawl](https://github.com/sourcelocation/dawl) | Stripe, App Store and Google Play. Pro is editors on lent decks and 10 GB of media (Free has 200 MB); everything else, AI assistants included, is free. `users.pro_until` is the one thing both servers check. What needs Pro is shown locked, saying why, and choosing it offers Pro. Promo codes and staff grants are subscriptions too. |
| Media | Go server | Uploaded by hash within the plan's quota to S3-compatible storage (R2); unused files are removed. |
| Notifications | `internal/notify` | Kinds written by the server, shown once on the next device, optionally by email and push. No inbox. A new kind needs no app update. |
| Reminders | `:client`, `internal/reminders` | Opt-in. Devices send what's due; the server pushes or emails, or a phone schedules its own. |
| Staff | `internal/staff`, web only | Code checks permissions, never roles. Staff work needs a passkey session. Every change is a log entry, and state is derived from the entries not undone. A new power is one kind with its permission, limit, areas and notifications. |
| Moderation | `internal/staff` | Warnings, restrictions (nothing reaches others) and bans; renames and deck takedowns. Weighted reports open cases; people review every case. |
| Agents | `:mcp`, `:cli` | MCP tools over a `Workspace`: hosted in the sync server (OAuth through Hydra), or local as `rondo-mcp`; both on any plan. |
| Export | Go server | A ZIP of everything a person owns, sent by email. |
| Jobs | Go server | River, queued in the same database: mail, pushes, exports, derivations and periodic cleanup. |
| Web app | `app/platforms/web` | A PWA under `/app`: SQLite in WebAssembly on OPFS, one tab at a time. Overlays live in the address. |
| Sites | `site/` | The landing page (static, only the logo and fonts from `brand/`, strict CSP) and the docs. The launch list lives in Listmonk. |
| Shipping | ops repository | A release is a pull request from master into production, titled with its version; merging it tags it, builds four images, and Flux deploys them to k3s behind Cloudflare. CI lints, tests and checks generated code. |

## Where a change goes

- A rule about what a row may be or who may change it: `:core`, and nowhere else.
- Anything between people or about money: the Go server, through the contract.
- A schema change: a migration in `server/db/migrations`, and the device's in `db/*.sqm`.
- What a device does with its own data, and its screens' state: `:client`.
- A screen's state and actions: the `:client` file named for its part of the app (`DeckScreens.kt`,
  `SharingScreens.kt`, `ProScreens.kt`…), with its items; a part with none yet gets a file of its
  own. What two screens both do is written once, beside the screens it belongs to.
- Any word in the app's own interface: `Strings`.
