# Architecture

**Map.** Local-first: each device keeps its account's data in SQLite and syncs in the background. `app/` is one Gradle build: `:core` (domain, rules, records, sync protocol, files; no I/O), `:client` (storage → services → screens), `:sync` (sync server, hosted MCP), `:mcp`, `:cli`, `platforms/web` (React PWA). `server/` is Go: people, sharing, Discover, mail, jobs; Kratos signs in, Hydra is agents' OAuth. One Postgres (`server/db/migrations`), one API contract (`server/api/rondo.yaml`), one origin routed by path (a new path needs the ingress, `vite.config.ts` and compose's proxy).

## Do
- Decide every write rule once, in `:core` (`thing.check(access, stored)`): devices check before writing, the sync server before storing.
- Layer domain → records → wire → storage → services → screens
- Put behaviour on the thing it's about
- Keep all state and actions in `:client` screens; UIs only draw and call.
- Put every UI word in `Strings`, and start new ideas from what Rondo leaves out.

## Don't
- Let a UI decide anything: what's offered, formats, limits, encodings.
- Link to another page to do an operation there, pass snapshots between screens
- Use strings for closed sets of kinds
- Add objects of static functions, top-level helpers, interfaces with one implementation, code generators or committed shell scripts.
