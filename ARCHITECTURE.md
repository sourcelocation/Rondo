# Architecture

A map of Rondo for people who change it: what runs where, who owns what, and the rules that hold
everywhere. Using Rondo and its API is covered in the [docs](https://rondo.matthewsource.com/docs/).

## The shape of it

- **Local-first.** Every device keeps everything its account can see (its own decks and the ones
  lent to it) in its own SQLite database, and reads and writes only that. Sync runs in the
  background. Without an account, everything works except syncing and sharing.
- **One set of rules.** What a write may do is decided by Kotlin in `app/core`, which runs on
  devices before they write and on the sync server before it stores. No rule exists twice.
- **Two servers, one PostgreSQL.** The Go server (`server/`) does everything that involves people
  or money: accounts, sharing, Discover, billing, media, mail and agents' consent. The Kotlin sync
  server (`app/sync`) moves rows between devices and Postgres and hosts the MCP server.
- **Identity off the shelf.** Ory Kratos signs people in, passwordless and headless: Rondo's own
  screens drive it. Ory Hydra is the OAuth server that AI assistants sign in through.
- **Screens in Kotlin, UIs per platform.** `app/client` holds every screen's state and actions. Each
  UI (React today, SwiftUI and Compose next) draws that state, calls those actions, and decides
  nothing itself.

## Scope

- **What Rondo leaves out**, on purpose: tags (decks and Browse's filters group notes), a search
  query language (Browse has filter pickers), saved searches, flag colours (a card is marked or not),
  an inbox (a notification shows once), comments, video, leech handling, per-learner study settings,
  pins, archiving, and daily time budgets, easy days and load balancing. New ideas start from this
  list.
- **Duels come later**, and the design leaves room for them: live duels on official decks with
  ratings the server keeps. The Curator role and its `decks.official` permission exist already,
  notifications can ride a realtime connection when there is one, and a restriction can gain a
  scope.
- **Libraries over infrastructure.** No hand-built code generators or preprocessors, and no
  committed shell scripts. An interface exists only when it has two real implementations.

## One hostname

Everything is served from `rondo.matthewsource.com`, routed by path at the cluster's ingress:

| Path | Served by |
| --- | --- |
| `/` | the landing page; it redirects share links (`/d/…`, `/i/…`), profiles (`/u/…`) and codes (`/redeem/…`) into the app |
| `/docs` | the documentation |
| `/app` | the web app |
| `/api`, `/media` | the Go server |
| `/sync`, `/mcp`, `/.well-known/oauth-protected-resource` | the sync server |
| `/auth` | Kratos's public API, without the prefix and without the `Origin` and `Cookie` headers browsers add |
| `/oauth2/…`, `/userinfo` and the OAuth discovery documents | Hydra |

One origin means the web app needs neither CORS nor cookies, and an assistant needs one URL:
`https://rondo.matthewsource.com/mcp`. Locally, the web app's dev server reproduces the same origin
on `http://localhost:23900` by proxying each path (`app/platforms/web/vite.config.ts`).

## Repository

Four standalone projects, each with its own toolchain, which meet only through a few contract files.

| Project | Contents |
| --- | --- |
| `server/` | The Go server; the API contract (`api/rondo.yaml`); the whole Postgres schema (`db/migrations`); the local stack (`compose.yaml`) |
| `app/` | One Gradle build: `core`, `client`, `mcp`, `cli`, `sync` and `platforms/web` |
| `site/` | The landing page (Astro) and the docs (Fumadocs), served by one image |
| `brand/` | Design tokens, fonts and artwork, compiled by [shasp](https://github.com/sourcelocation/shasp) into `brand/dist` |

The contracts:

- **`server/api/rondo.yaml`** (OpenAPI 3.0.3) is the API. It generates the Go server's interfaces
  and models, the Kotlin models in `:core` and the HTTP client in `:client`, and the docs' API
  reference. An API change starts here.
- **`server/db/migrations`** is the schema of the one database. Goose applies it (`rondo migrate`);
  the sync server only checks that the version is new enough, and its tests load the same files.
- **`brand/dist`** is everything the brand produces, committed: CSS for the web, `tokens.json` and
  the mark for mail, Swift, Kotlin and Android resources for the native apps.

Inside `app/`:

| Module | What it holds |
| --- | --- |
| `:core` | The rules (`Rules.kt`) with the deck tree and access (`Tree`, `Access`); markup (`Markup.kt`) and its mapping to the editor (`Editor.kt`); templates and card layout (`Templates.kt`); FSRS-6 and its optimizer (`Fsrs.kt`, `Optimizer.kt`); learning events and levels (`Learning.kt`); ids, clocks and order keys (`Ids.kt`); copying and search (`Workspace.kt`). Multiplatform, without I/O. |
| `:client` | Everything a device does: the store, sync, the account, media, Anki import, study sessions, reminders, and every screen (`*Screens.kt`) with its text (`Strings.kt`). Multiplatform: JVM and JavaScript now, iOS and Android next. |
| `:mcp` | The MCP tools, over `:core`'s `Workspace`. |
| `:cli` | `rondo-mcp`: the same tools over stdio, on a replica of its own. |
| `:sync` | The sync server (Ktor): push, pull, fetch and hosted MCP, over Postgres. |
| `platforms/web` | The React web app over `:client`'s JavaScript build. |

## Data

### Three kinds of rows

- **The pile**: decks, templates (note types) and notes. Content that can be shared: each row has
  one owner (`owner_id`) and a clock (`v`). The newest version of a row wins, whole rows at a time.
- **The learner**: `events`, append-only and per person, plus one `settings` row (answer buttons,
  theme, text size, time zone, FSRS weights, and when and how to be reminded). Events are reviews,
  suspensions, marks, burying, resets, unlocks, snapshots of progress made elsewhere (imports,
  copies), and voids that cancel an event. Two devices' answers never conflict: every event counts.
  A card's state is never stored on the server; it is folded from its events wherever it is needed.
- **People, access and money**: `users` (with their usernames, flags and standing) and `names`
  (their history), `shares` (a deck lent to someone, as viewer or editor), `invites`,
  `publications` (Discover), `subscriptions`, `promo_codes` and `media`; the staff's `roles`,
  `staff_actions`, `reports`, `cases`, `user_ips` and `network_restrictions`; and `notifications`,
  `push_devices`, `summaries` and `reminder_state`.

The sync server writes the first two kinds and the Go server the third; neither writes the other's
tables. Synced tables have no foreign keys between them: pushes arrive in any order, and `:core`
checks references instead.

### Ids, clocks and order

- Ids are UUIDv7, so ordering by id is ordering by creation. Imports make ids from their source's
  timestamps (`Ids.at`) and keep its order. Built-in templates have reserved ids and are never
  stored.
- `v` is a hybrid logical clock (`Hlc`): milliseconds since 2025, a counter and a device number in
  53 bits, which JavaScript's numbers hold exactly. Devices observe every clock they pull, so their
  next edit is newer.
- Siblings are ordered by fractional keys (`Positions`): strings that sort between their neighbours,
  so moving a deck rewrites one row.

### Ownership, borrowing and deletion

- A note belongs to its deck's owner, a template to whoever made it, and an owner never changes.
  Borrowers change a lent deck's notes only as editors, and only while its owner has Pro.
- Deleting sets `deleted_at`. Deleted decks and notes stay in Recently deleted for 30 days; then the
  sync server purges them, and streams last synced before the purge start over.
- What others borrow doesn't disappear. When its owner deletes a lent deck (or one above it), or
  their account, the lent decks stay with `owner_id` NULL: authorless, read-only, and still synced
  to the people who borrow them. Authorless rows that nobody borrows any more are deleted.

### Content

- **Markup.** A field's text is one string: paragraphs, lists, pipe tables and `$$` math, with
  inline marks, `$math$`, `{{c1::cloze::hint}}` and `{base|reading}` ruby (`Markup.kt`). The web
  editor edits ProseMirror documents, which `Editor.kt` converts to and from markup, so editors only
  wire commands.
- **Templates.** A template is fields and card definitions made of blocks (a field, a label, a
  divider, a type-in), optionally with one card per cloze number or occlusion group (`each`).
  `Templates.layout` turns a note and a card into `Piece`s, which each UI draws natively. A text
  field can be *in the deck's language* (`tts`): only those fields are read aloud, in that
  language's voice, and spell-checked in it; a deck without a language reads nothing aloud. The
  built-in types mark Front (Vocabulary: Word and Example).
- **Notes** are a template's fields filled in, nothing more: no tags.
- **Anki 1:1.** An imported note type can stay Anki's HTML (`kind` 2). Its notes are read-only and
  shown from that HTML and CSS; "Convert to copy" makes them Rondo notes (`AnkiText.kt`).
- **Media** are named by their SHA-256 and never change. Fields hold the hash (inside Anki HTML, as
  `rondo-media:<hash>`).

## Rules

`Rules` in `:core` decides every write. `deck`, `note`, `template`, `event` and `settings` each take
the stored row (if any), the new one and an `Access` (who is writing, the deck tree, what is lent to
them and which owners have Pro), and return an error code or null. Devices call them before writing
(`Library`), the sync server before storing (`Sync.push`), and both MCP workspaces write through one
of the two. The server refuses a row only when the device's picture was out of date, and the device
shows that as a sync issue.

Among what they guarantee:

- an owner never changes, and a note's owner is its deck's;
- a borrower can't move or delete the deck lent to them, nor take a deck out of it;
- Anki 1:1 notes and templates don't change;
- deck trees have no cycles and are at most 32 deep;
- no row's clock is more than ten minutes ahead of the server's.

## Sync

A device syncs a few seconds after an edit, every five minutes while signed in, and when asked. A
sync uploads new media, then pushes, then pulls (`Syncer.kt` on devices, `Sync.kt` on the server).

**Push.** The device sends its dirty rows and unpushed events, parents before children, in batches.
The server skips what isn't newer than what it has, checks the rest with `Rules`, and stores it.
Every owner has a counter (`counters.seq`); stored rows take its next numbers under that row's lock,
so a reader never skips a row. Refused rows come back with a reason: the device keeps its version in
the sync issues panel (to save as a copy or let go) and fetches the server's.

**Pull.** Rows come in streams: `me` (everything the account owns, its events and settings) and one
per lent deck (that deck's subtree, its notes and the templates they use). For each stream the
device sends its cursor, the last `seq` it saw, and gets newer rows, a thousand at most per page.
Each page carries a fingerprint of the stream's deck ids; when it differs from the device's, the
ids come too, and the device removes decks that left and fetches decks that joined (`/sync/fetch`).
A cursor older than the owner's last purge makes the device start that stream over.

**On the device,** pulled rows replace local ones unless a newer local edit waits to be pushed.
Card states are folded again for the notes whose events changed; new FSRS weights fold everything
again.

Every request to Rondo's servers carries `Rondo-Protocol: 1`; an app too old for the server gets
`426` and asks to be updated.

## Devices

`Rondo` puts a device together: `Store` (SQLite through SQLDelight, with rows shaped like the API's
models, dirty flags, and folded card states in `card_state`), `Net` (Ktor and the generated API
client), `Account`, `Syncer`, `MediaFiles`, `Library` (every edit, through the rules), `AnkiImport`,
study `Session`s and `Reminders`. Each platform provides a `Platform`: the database driver, a little
key-value storage for the session token, the archive formats imports need, passkeys, and where it
has them, notifications it schedules itself and pushes from the server.

**Screens.** A `Screen<S>` loads a state `S` and offers its actions as methods. A UI watches a
screen, draws `state` and calls actions; failures arrive as notices. Live screens reload whenever
the database changes, so a sync, an edit in another screen or a background job shows up by itself.
Actions run in the device's scope, not the screen's, so one finishes even when the UI closes its
screen right after calling it. An action whose result the UI needs (the deck just created, the
copy just made) takes a callback; one that may need a yes first (moving a deck out of one others
borrow, signing out with unsynced changes) calls back with the question, and runs once called again
confirmed. Every word of the app's own interface comes from `Strings`, and every rule stays out of
the UI. What the server says to people (notifications and mail) the server writes.

The device's database moves forward with SQLDelight migrations (`db/*.sqm`); `meta.schema` records
where it stands, and stays when signing out empties the rest.

**Studying.** A `Session` builds the day's queue: learning cards when they're due, then reviews (the
ones most likely forgotten first), then new cards from open levels, within the deck's daily limits
and one card per note a day. An answer is an event, which `Learning.fold` turns into the card's
memory with FSRS-6 (`Fsrs.kt`, checked against the reference implementation). Days start at 4 am in
the learner's time zone. About once a week, with enough reviews, the device fits FSRS's weights to
its learner (`Optimizer.kt`); the weights sync as a setting.

**Levels.** Under a gated deck, sub-decks open in order: each once enough of the one before it is
learned (its `unlock_at` percent, all of it by default), or when unlocked by hand. Opening is
recorded as an event, so a level stays open.

**Anki import** runs on the device: `.apkg` and `.colpkg` in the old and the new (zstd, protobuf)
formats, with media, where each card stands, and optionally every past review. It makes rows like
any other edit and passes them through the rules.

### The web app

`app/platforms/web` is React over `:client`'s JavaScript build: ES modules with TypeScript
declarations, written to `src/kotlin` by Gradle, which the dev server keeps rebuilding.

- SQLite is the official WebAssembly build, in a worker, on the origin private file system
  (`db.worker.ts` speaks SQLDelight's worker protocol). That file can't be open twice, so Rondo runs
  in one tab at a time (a Web Lock), and another tab can take it over.
- It is a PWA under `/app`. Its service worker (`src/sw.ts`, with Workbox) serves the app from the
  cache, so it works offline, and shows the server's Web Push notifications.
- The editor is TipTap with Rondo's cloze mark and ruby node; math is KaTeX. Anki 1:1 cards render
  in a sandboxed iframe.
- **Places and levels.** Home, Browse, Discover, Progress and Profile are places: a sidebar on
  desktop (with the deck trees, dragged to move decks), tabs on phones. Staff is a sixth, on the web
  only, for people with staff permissions; each can hide it on a device. Every other page sits one
  level below another (`/decks/:id` under its parent deck or Home, `/profile/templates` under
  Profile), and the address says so. ← and Esc go up one level, going back when that's where the
  person came from, so they never leave the app or land somewhere unexpected (`routing.ts`). Each
  place remembers where it was left.
- **Overlays live in the address.** The note editor (`?note=`), the deck sheets (`?sheet=`) and the
  new-deck dialog (`?new=deck`) open over the page that asked for them, so Back closes them and a
  reload keeps them. The editor is one component: over Home, a deck, Browse or a study session
  (which carries on after saving), and as a page at `/notes/:id` for direct links.
- **Writing on the card.** Notes are written where their fields show (Front, then Back), with
  formatting in a bubble over the selection. Note types are chosen from previews in a picker, and
  shaped on the card too (fields on each side, and the questions it asks); every card's blocks are
  one step further in.

## Accounts

People sign in through Kratos's API flows, driven by `Account.kt` the same way on every platform:
an email code (signing up and signing in are one step), a passkey, or an ID token from Sign in with
Apple or Google. A flow ends with a session token, which devices send as a bearer token to `/api`
and `/sync`; both servers check it with Kratos and remember the answer for a minute.

Kratos has no UI of its own. Its webhooks reach the Go server: registration creates the `users`
row (named `user<random>` until the person picks a name), and its courier hands every message to
Rondo's mail, which sends it from a River job in Rondo's branded layout.

Account security lives in **Profile › Security**:

- **A second step**: an authenticator app (Kratos's `totp`) with recovery codes (`lookup_secret`).
  Kratos requires the highest assurance a person has set up (`required_aal: highest_available`), so
  until a session has taken the step, both servers answer `second_factor_required` and the app asks
  for the code.
- **A lost authenticator**: a code by email schedules removing the second step 7 days later (a River
  job), so a stranger with the inbox can't do it quietly. Signing in with the step, or cancelling,
  stops it, and `/api/me` shows it pending meanwhile.
- **Linked accounts**: the app gets an ID token from Google or Apple, the Go server verifies it
  (`internal/idtoken`) and adds the credential through Kratos's admin API. A provider account linked
  to someone else is refused.
- **A new email is verified first**: a code goes to the new address (`email_changes`), and entering
  it moves the identity there and tells the old address.
- **Signed-in devices** are Kratos's sessions, listed and ended from the app.

Deleting the account, and these changes, need a sign-in from the last ten minutes, or a fresh code.

## Profiles and names

Everyone has a username, unique and part of their address (`/u/:username`), and can add a display
name in any script and a flag (a country, drawn from `country-flag-icons`). Signing up gives
`user<random>`; the first sign-in offers a one-tap setup, filled from the Google or Apple name and
Cloudflare's guess of the country. Sharing and publishing need it confirmed (`profile_required`).

- Usernames follow `internal/names`: `[a-z0-9_]{3,20}`, reserved words, a profanity filter. They
  change once per 30 days (the first choice and staff resets don't count); an old one is held for
  30 days and leads to the new one. `names` keeps the history.
- A profile shows the person's decks on Discover and, unless they hide it, their activity: reviews,
  days, streaks and minutes, from their review events in their time zone with days starting at 4 am
  (cached for an hour), and the cards learned that their devices last counted.
- A restricted or banned person's profile and decks aren't shown to others.

## Notifications and reminders

**Notifications** are written by the server. Each kind (`internal/notify`) sets its words, its
link, how apps show it (a modal, a toast, or not at all) and whether it also goes by email or push.
`notify.Send` writes the row and queues the email and push jobs in the same transaction. Apps get
unseen notifications with `/api/me`, show each once on whichever device opens next (modals one at a
time), and mark it seen. There is no inbox: what a notification is about stays visible where it
belongs, such as a removed deck's page. A new kind needs no app update.

**Pushes** go to `push_devices`: browsers through Web Push (Rondo's VAPID keys, `webpush-go`), and
the apps through APNs and FCM once they exist. A device its push service no longer knows is
forgotten.

**Reminders** are opt-in; their time (minutes after local midnight) and email are synced settings.

- After each sync, a device sends a summary (`Reminders.kt`): the cards due on each of the next 7
  days if nothing is studied, within each deck's limits, and the cards learned. Card states stay on
  devices; the server counts the summary's days from the day it arrived.
- A device that schedules notifications itself (the phone apps, through `Platform.notifications`)
  schedules the next 7 days from its own card states, drops today's once studied, and tells the
  server it's covered until then (`local_until` on its push device).
- The server's job, every 15 minutes, reminds the people whose time falls in the window in their
  time zone, unless they studied that day or nothing is due: a push to the devices with reminders on
  that aren't covered, and an email if they chose it. At most once a day; after 7 ignored in a row,
  reminders pause, with a notification, until the person studies again.

## Staff

Staff tools are on the web only. Roles (`roles`) grant permissions, which code checks (never roles)
and `/api/me` lists as strings, so new ones don't break old apps. Admins have every tool except
granting admin; moderators have the moderation tools; Support and Curator are defined but can't be
granted yet. The owner is `RONDO_OWNER_EMAIL`, matched to a verified Kratos identity at launch, and
isn't stored. Rank (owner > admin > moderator) decides whom someone may act on: only people strictly
below them.

Staff powers work only in a session that signed in with a passkey (`/api/me` says `staff_locked`
otherwise), and the weightiest need one from the last 15 minutes. `RONDO_STAFF_PASSKEY=off` lifts
this for local development, on an `http://localhost` origin only.

### The log and undo

The log is the truth. Every staff change is an entry in `staff_actions`: who, what kind, whom or
which deck, the reason and a note, until when, and its data. What entries decide (roles, a person's
standing, removed decks, names, granted Pro, network restrictions, cases) is derived from the
entries not undone, one area at a time (`internal/staff/areas.go`). Doing is inserting and deriving;
undoing is marking undone and deriving; an expiry is a derivation scheduled for that moment. Effects
outside the database (Kratos, Hydra, Stripe) follow the derived state from a River job, so they
retry and are safe to repeat.

Each kind is one registry entry: its permission, its target, whether it needs a reason or a fresh
passkey, its hourly limit, the areas it touches, optional checks and extras, and what to tell the
person when it's done and when it's undone. Whoever acted, or anyone ranked above them, can undo an
entry, and admins can revert everything someone did since a moment. Going over a limit refuses the
action, freezes the person (a `staff.freeze` entry) and tells the owner, who has no limits. Every
entry has a page to share (`/staff/log/:id`).

### Moderation

A preset reason and a note go with every step, and the form suggests the next one from the last 180
days: a warning, a restriction, a ban.

- A restriction stops what reaches others: publishing, inviting and profile changes are refused (one
  `social` check), their invitations stop working, their reports count for nothing, and their
  profile and decks disappear from others' view. Studying and syncing go on.
- A ban deactivates the Kratos identity and ends its sessions, revokes every agent's access, and
  turns off renewal where the store lets the server do that (dawl's `Renewer`: Stripe), noting
  which; the reason goes by email. When the ban ends or is undone, they can sign in again and those
  renewals come back on; agents are theirs to reconnect. Identities are never deleted, so the
  address and its Google or Apple accounts can't start over.
- A rename resets someone's names to `user<random>`. A deck can be removed from Discover, or taken
  down, which also ends its shares (kept in the entry, so undoing brings them back). Addresses and
  ranges can be restricted too.
- Private decks are invisible to staff.
- The Go server records the addresses people use (`CF-Connecting-IP`, at most hourly and on every
  social action, for 90 days) and an HMAC of their normalized email (`email_key`). An admin's view
  of a person lists related accounts: a shared address (an IPv6 /64), the same email, similar
  current or past names (`pg_trgm`). Emails, addresses and past names are for admins only.

### Reports and cases

Signed-in people report decks and people: once per case, at most 20 a day. A report's weight is
fixed when it's filed: the account's age (0.2 under a day, 0.5 under a month, else 1) times its
record ((upheld + 1) / (resolved + 2) × 2). A case opens once its weights reach 3 + followers/200,
or at the first report of illegal content, which names the law, explains, and is made in good faith.

Cases sit in lanes: illegal, safety, spam, other, waves and flags. Every 6 hours a job compares the
day's reports, publications and signups with the last 28 days (median + 5 × MAD) and opens a wave
case on a spike; publishing raises flags (a day-old account publishing several decks, many
publications from one address). A staff action on a case upholds it, `case.dismiss` is an entry like
any other, and reporters hear when theirs was reviewed. People review every case; nothing acts on
its own.

## Agents

The MCP tools (`:mcp`) work on a `Workspace`, which two places provide:

- **Hosted**, in the sync server: `PostgresWorkspace`, writing through `Sync.push`. Assistants find
  Hydra through `/.well-known/oauth-protected-resource`, register themselves (dynamic client
  registration) and sign in with OAuth. Hydra's login step lands in the web app, which accepts it
  for whoever is signed in there (`PUT /api/oauth/login/{challenge}`), and its consent page is the
  app's (`/app/oauth/consent`). The sync server checks tokens by introspection, for the audience
  `…/mcp`. Hosted agents need Pro.
- **Local**, `rondo-mcp` (`:cli`): a device of its own, with a replica in `~/.rondo`, signed in with
  an email code and writing through `Library` like any device.

## The Go server

`server/` is Echo with handlers implementing the interface generated from the contract
(`internal/handlers`), queries generated by sqlc (`db/queries`, `internal/store`), and River for
jobs (`internal/jobs`), queued in the same database. Beside them: `internal/ory` (Kratos and
Hydra), `internal/staff` (roles, the log and every kind), `internal/notify` (notifications, email
and push), `internal/reminders`, `internal/pro` (Pro from subscriptions), `internal/names` and
`internal/idtoken`.

- **Sharing**: shares, invitations (a link for anyone, or one for a single email address),
  publishing to Discover, and following, which is borrowing as a viewer. Lending as an editor needs
  the owner's Pro. Invitations and Discover show a deck the same way (`PublicDeck`: the deck, its
  sub-decks and a few sample notes), from the server, before anything is borrowed or synced. `/api/me`
  lists the decks a person lends, so devices can ask before moving decks out of them.
- **Discover** reads only `publications`, which keep a copy of what a listing shows (name, language,
  author, followers, notes, and follows in the last 7 days for Hot), refreshed every 10 minutes and
  on every publish and follow. Each sort (Top, New, Hot) has its own partial index, and searching
  by name a trigram one. A deck's preview reads the deck live.
- **Billing** goes through [dawl](https://github.com/sourcelocation/dawl): Stripe checkout and its
  portal on the web, App Store and Google Play purchases verified for the apps, and every store's
  notifications. A store's notification is answered with the subscription as the store has it
  now, so saving is safe in any order. Each subscription is stored, and `users.pro_until` is the
  latest of when they stop granting access, by dawl's one rule (`State.Until`); that one column is
  what both servers check, and the switch from free to Pro is a notification. Promo codes come in
  batches (single-use, a number of days each). Redeeming one, on the web, adds a subscription from
  the `promo` provider, active and not renewing, that starts when the current Pro ends, and is
  refused while a paid one renews; staff grants are subscriptions from `grant`. A new account email
  goes to Stripe too, for its receipts.
- **Media**: a device asks to upload a hash, within its plan's quota, and gets a presigned URL
  (S3-compatible storage; Cloudflare R2 in production) or the server's own `/media/<hash>`. A file
  counts once it reads back with the right hash. Reading `/media/<hash>` redirects to the file.
  Files that no note uses, over a week old, are removed by a daily job.
- **Export**: a ZIP of everything a person owns, made by a job and sent by email.
- **Jobs**: mail, notifications' email and pushes, exports, a new email for Stripe, and the staff's
  scheduled derivations and second-step removals; and every so often, unused media (daily), old
  addresses and seen notifications (daily, after 90 days), spikes (every 6 hours), Discover's copy
  (every 10 minutes) and reminders (every 15 minutes).
- Errors are `Problem`s with a code from the contract; only unexpected ones are logged and reported.

## The sync server

`app/sync` is Ktor over JDBC. Rows travel to and from Postgres as JSON (`to_jsonb` out,
`jsonb_populate_record` in), so its SQL never lists a table's columns. Besides the sync routes and
hosted MCP, it runs `Maintenance` every hour on one replica at a time (an advisory lock): purging
deletions older than 30 days and authorless rows nobody borrows. It won't start on a schema older
than it needs.

## Brand and sites

`brand/` holds the tokens (W3C design tokens), fonts and artwork; `pnpm build` there runs shasp,
which writes `brand/dist`. The web app imports its CSS, mail reads `tokens.json` and the mark, and
the native apps will read the Swift and Kotlin output. `site/` builds the landing page and the docs
into one image; the docs' API reference is generated from the contract.

The landing page is static Astro. Its motion lives in `site/landing/src/motion`, one module per
scene over GSAP and Lenis, with an OGL shader for the light behind the page; the markup works
without it, and with reduced motion everything is shown in place. It takes only the logo from
`brand/`, through one inline sprite. The site's Content-Security-Policy (`site/Caddyfile`) allows
only its own files, so the pages carry no inline scripts, styles or `data:` URLs, and the build
inlines nothing.

Until the launch (`site/landing/src/launch`), the site's actions are a star on GitHub and a launch
list (one email on launch day, nothing else) in place of opening and installing the apps.
`/launch.js`, built from the launch time and `PUBLIC_RONDO_RELEASE` (`auto` follows the clock;
`pre` and `live` hold it), puts the mode and the visitor's platform on `<html>` before a page
paints, and a page open at the launch turns by itself. The list's sign-up is a stub until the server
keeps it. `/download` lists every platform; the stores' links are filled in at the launch
(`site/landing/src/content/downloads.ts`).

## Running and shipping

- **Locally**, Rondo keeps to ports 23900–23912 (listed in `server/compose.yaml`), so it runs beside
  other projects. `docker compose up -d` in `server/` starts Postgres, Kratos, Hydra and Mailpit; the
  README shows how to run the two servers and the web app.
- **Releases.** A version tag builds four images (`server/Dockerfile`; `app/Dockerfile`, targets
  `sync` and `web`; `site/Dockerfile`) and points the ops repository at them. Flux deploys them to
  k3s; Traefik routes the hostname as above; Postgres runs on the host and mail goes through
  Stalwart. Cloudflare sits in front, and only Cloudflare reaches the server. Errors go to a
  self-hosted, Sentry-compatible tracker.
- **Checks.** CI runs golangci-lint and the Go tests against Postgres and makes sure generated code
  is current; runs spotless and the Kotlin tests (`:core` on the JVM and in JavaScript, the sync
  server against Postgres with real clients); and type-checks and builds the web app. The brand has
  a job of its own that checks `brand/dist` is what its sources produce.

## Where a change goes

- A rule about what a row may be or who may change it: `:core`, and nowhere else.
- Anything between people or about money (sharing, billing, mail, media storage): the Go server,
  through the contract.
- A staff power: a kind in `internal/staff`, with its permission, limit, areas and notifications.
- Something to tell people: a kind in `internal/notify`; the apps show it without an update.
- What a device does with its own data, and its screens' state: `:client`.
- A UI draws screen states and calls screen actions; it decides nothing.
