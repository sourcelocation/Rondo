# To do

Work that's decided but not done. Take items from here, and remove them once they're done; decisions
that come with them go into [ARCHITECTURE.md](ARCHITECTURE.md).

## Rondo

- **The apps on a self-hosted server.** The client's server is fixed to `rondo.matthewsource.com`,
  so a server of one's own works with the web app and `rondo-mcp` only.
- **Images for ARM too.** Releases build `linux/amd64` only, so `server/self-host` doesn't run on
  ARM servers (a Raspberry Pi, Ampere, Apple silicon).

## Landing page (`site/landing`)

- **The link preview says what Rondo is**: `og.png` shows the headline and the app, not the logo
  alone. The home page's title says "Anki", for people searching for an Anki alternative.
- **Proof it handles big collections.** Replace the Anki section's animations with cards stacked
  into a deck facing the viewer, at a rapid pace, coming in from the top left and the top right.
- **For people coming from Anki**: name the dialog they know ("upload or download?") where the
  Sync section says there's nothing to merge, and link the import guide from the Anki section.
- **Discover, for newcomers**: everything is framed against Anki today. Show picking a deck on
  Discover and studying it seconds later.
- **Sharing and studying together**: invitations, share links, publishing on Discover, and editors
  on your decks (Pro).
- **AI assistants**, free on every plan: Rondo is an MCP server; Claude or ChatGPT can search your
  decks and turn what you're reading into cards (`site/docs/content/agents.mdx`).
- **What's free and what Pro adds**, as `site/docs/content/plans.mdx` has it: everything is free,
  AI assistants included; Pro is editors on the decks you share and 10 GB for pictures and sounds
  instead of 200 MB.
- **An FAQ**: what's free and what Pro adds, how Rondo is paid for, tags and add-ons, going back to
  Anki, running your own server, and where your data lives.
- **A press kit**: the logo, screenshots, a short bio and a contact address, for writers and
  creators.
