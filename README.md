<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset=".github/assets/hero-dark.svg">
    <img alt="Rondo: spaced repetition that comes back around. One card, A, keeps returning between new cards B, C and D, after 1 day, then 4 days, then 2 weeks." src=".github/assets/hero-light.svg" width="100%">
  </picture>
</p>

<p align="center">
  <b>Open-source flashcards with a modern memory model.</b><br>
  FSRS scheduling, native apps for iPhone, iPad, Mac, Android and the web, offline-first sync,<br>
  and your Anki decks with their review history.
</p>

<p align="center">
  <a href="https://rondo.matthewsource.com/app/"><b>Open the web app</b></a>
  &nbsp;·&nbsp;
  <a href="https://rondo.matthewsource.com/docs/">Docs</a>
  &nbsp;·&nbsp;
  <a href="https://discord.gg/GfftwYzbdV">Discord</a>
  &nbsp;·&nbsp;
  <a href="#coming-from-anki">Coming from Anki</a>
  &nbsp;·&nbsp;
  <a href="#run-your-own">Run your own</a>
  &nbsp;·&nbsp;
  <a href="#how-it-works">How it works</a>
</p>

<p align="center">
  <a href="LICENSE"><img alt="License: AGPL-3.0" src="https://img.shields.io/badge/license-AGPL--3.0-2F3BFF?style=flat-square&labelColor=141413"></a>
  <a href="https://github.com/sourcelocation/rondo/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/sourcelocation/rondo/ci.yml?branch=main&label=ci&style=flat-square&labelColor=141413"></a>
  <img alt="Platforms: iOS, iPadOS, macOS, Android, web" src="https://img.shields.io/badge/platforms-iOS%20%C2%B7%20iPadOS%20%C2%B7%20macOS%20%C2%B7%20Android%20%C2%B7%20web-2F3BFF?style=flat-square&labelColor=141413">
  <a href="https://github.com/open-spaced-repetition/fsrs4anki/wiki/ABC-of-FSRS"><img alt="Scheduler: FSRS-6" src="https://img.shields.io/badge/scheduler-FSRS--6-2F3BFF?style=flat-square&labelColor=141413"></a>
</p>

> [!NOTE]
> Rondo is pre-release. The web app comes first; the iPhone, iPad, Mac and Android apps follow on the same core. Until the hosted service opens, [run it from source](#run-your-own).

<picture>
  <source media="(prefers-color-scheme: dark)" srcset=".github/assets/screenshot-dark.png">
  <img alt="Rondo's Today screen on a Mac and a study card on an iPhone" src=".github/assets/screenshot-light.png" width="100%">
</picture>

## Why Rondo

In music, a *rondo* is a form whose theme keeps returning between new episodes: **A B A C A D A**. Lasting memory forms the same way. What you're learning comes back between new material, a little later each time. Rondo schedules those returns and stays out of the way.

- **FSRS-6 scheduling.** You choose how much you want to remember (90% by default), and Rondo fits the schedule to your own answers.
- **Native on every device.** SwiftUI on iPhone, iPad and Mac, Jetpack Compose on Android, React on the web, all over one shared Kotlin core.
- **Offline first.** Every device keeps all your decks and works without an account. The newest edit wins, and reviews never conflict: answers from two offline devices all count. There are no "keep this side or that side?" dialogs.
- **Cards without HTML.** Cloze, image occlusion, LaTeX typed straight into the text, furigana and pinyin, typed answers and text-to-speech, all drawn natively. Build your own note types from blocks.
- **Your Anki decks, history included.** `.apkg` and `.colpkg` files are read on your device, with their media and reviews, made editable or kept exactly as they look in Anki.
- **Study together.** Invite people, share a link, or publish on Discover. Decks open level by level, and everyone keeps their own progress.
- **Works with AI assistants.** An [MCP server](https://rondo.matthewsource.com/docs/agents) lets your assistant search decks and draft cards: through Rondo with OAuth, or on your computer with `rondo-mcp`.
- **Open all the way down.** AGPL-3.0, a spec-first API, and a backend you can run yourself.

## Coming from Anki

1. In Anki, choose **File › Export** and pick **Anki Deck Package** or **Anki Collection Package**. Include scheduling and media.
2. In Rondo, choose **Import**, keep **Make it editable** and **Include review history** on, and select the file. The package is read on your device; the web app never uploads it as a whole.
3. Rondo says how many notes and media files came in, and what didn't fit.

<p align="center">
  <img alt="Importing an Anki deck: choose the package, pick the options, read the report" src=".github/assets/demo-import.gif" width="720">
</p>

Your review history comes along, so cards continue on their current schedule. Anki's standard note types become Rondo's own; others become note types of yours, with formatting, clozes, MathJax and furigana carried over. Or keep a deck exactly as it looks in Anki, read-only, and convert it whenever you like. [Full guide →](https://rondo.matthewsource.com/docs/importing)

## How it works

```
 Web (React)    iPhone · iPad · Mac (SwiftUI)    Android (Compose)       AI assistants
      └────────────────┬──────────────────────────────┘                        │
       :client — SQLite, sync, sign-in, study, import, screens (Kotlin)       MCP
       :core   — the rules, models, markup, FSRS-6, levels (Kotlin)          │
                       │                                                       │
    rondo.matthewsource.com:  /  /docs  /app ──► static files                  │
    /api /media ──► Go server ──────────────┐        /sync /mcp ──► sync server (Kotlin, :core)
    /auth ──► Kratos ───────────────────────┤                                  │
    /oauth2 ──► Hydra ──────────────────────┴──────► one PostgreSQL ◄──────────┘
```

- **Local-first.** Apps read and write their own SQLite copy and never wait on the network. Decks, note types and notes are rows with a clock; the newest version of a row wins.
- **Progress is a log.** Reviews, suspensions and flags are events that are never overwritten, so two offline devices can't lose each other's answers. Card states are folded from them.
- **One set of rules.** `:core` decides what a write may do; devices check it before writing, the sync server again before storing. The same Kotlin runs in the apps and on the server.
- **Spec-first.** [`rondo.yaml`](server/api/rondo.yaml) generates the Go server interfaces, the Kotlin models and client, and the API docs.

[ARCHITECTURE.md](ARCHITECTURE.md) has the whole map: who owns what, the rules every write follows, and how sync works.

## Run your own

The backend is a Go server and a Kotlin sync server over one PostgreSQL, with [Ory Kratos](https://www.ory.sh/kratos/) for sign-in, [Ory Hydra](https://www.ory.sh/hydra/) for assistants' OAuth, and any S3-compatible store (or a folder).

To host Rondo on a server of your own, [`server/self-host`](server/self-host) runs the released images behind Caddy with one Compose file: [Run your own server](https://rondo.matthewsource.com/docs/self-hosting) walks through it. What follows runs Rondo from source, for development.

```bash
git clone https://github.com/sourcelocation/rondo.git
cd rondo/server
docker compose up -d                        # Postgres, Kratos, Hydra, Mailpit
set -a; . dev/api.env; set +a               # the Go server's development settings
go run ./cmd/rondo migrate && go run ./cmd/rondo serve   # the Go server, :23901
```

```bash
cd app && ./gradlew :sync:run                       # the sync server, :23902
cd app/platforms/web && pnpm install && pnpm dev    # the web app, http://localhost:23900/app/
```

The web app builds the Kotlin client with Gradle, rebuilds it as you edit, and proxies the servers, Kratos and Hydra, so it is one origin as in production. Sign-in codes arrive in Mailpit at http://localhost:23908. `docker compose --profile full up` runs the servers in containers too. Rondo keeps to ports 23900–23912 ([`server/compose.yaml`](server/compose.yaml) lists them), so it runs beside other projects.

`server/compose.yaml` is for development, and its secrets are public. Releases build four images (`server/Dockerfile`; `app/Dockerfile`, targets `sync` and `web`; `site/Dockerfile`); production runs them on k3s behind one hostname, routed by path.

## Repository

Four standalone projects, each with its own toolchain, connected only through a few contract files (the API spec, the schema and the design tokens).

| Project | Inside |
| --- | --- |
| [`server/`](server) | The Go server: accounts, sharing, Discover, billing, media, mail and consent: Echo, sqlc, Goose, River. Owns the API contract and the schema. |
| [`app/`](app) | One Gradle build: the Kotlin core and client (SQLDelight, Ktor), the MCP tools, `rondo-mcp`, the sync server, and the [web app](app/platforms/web). |
| [`site/`](site) | The landing page (Astro) and the [docs](site/docs) (Fumadocs). |
| [`brand/`](brand) | Design tokens, fonts and artwork, compiled into CSS, Swift, Kotlin and Android resources. |

<details>
<summary><b>Build and test</b></summary>

```bash
# Go server: lint, tests (with the compose Postgres)
cd server && golangci-lint run && go test ./...

# Kotlin: format, the rules on JVM and JS, the client, the sync server against Postgres and real clients
cd app && ./gradlew spotlessCheck :core:allTests :client:jvmTest :sync:test

# Web app
cd app/platforms/web && pnpm format:check && pnpm typecheck && pnpm build

# Sites: landing on :23911, docs on :23912
cd site && pnpm install && pnpm dev
cd site && pnpm dev:docs
```

</details>

## Contributing

Issues and pull requests are welcome. For anything bigger than a fix, please open an issue first so we can agree on the approach. Changes to the API start in the [spec](server/api/rondo.yaml); the rules every write follows are in [`:core`](app/core).

Contributions are accepted under the [Contributor License Agreement](CONTRIBUTOR_LICENSE_AGREEMENT.md), which lets the project be relicensed later (for example to offer commercial licenses). Questions: support@matthewsource.com.

## License

Rondo is free software under the [GNU Affero General Public License v3.0](LICENSE). The Rondo name, mark and logo are trademarks and are not covered by the license. Forks need their own name and mark; see [`brand/artwork`](brand/artwork/README.md).
