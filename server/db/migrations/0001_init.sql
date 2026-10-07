-- +goose Up
-- The whole schema. Go writes the first group of tables, the sync server the second; neither writes
-- the other's. Synced tables have no foreign keys between each other (pushes arrive in any order and
-- :core enforces references); only `users` is referenced, because account deletion relies on it.

CREATE TABLE users (
  id              uuid PRIMARY KEY,         -- the Kratos identity id
  name            text,                     -- display name, required before sharing or publishing
  pro_until       timestamptz,              -- written from dawl's state, read by the sync server
  stripe_customer text,
  created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE shares (
  deck_id    uuid NOT NULL,                 -- the lent deck; decks belong to the sync server
  user_id    uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  role       smallint NOT NULL,             -- 1 viewer, 2 editor
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (deck_id, user_id)
);
CREATE INDEX shares_user ON shares (user_id);

CREATE TABLE invites (
  id         uuid PRIMARY KEY,
  token_hash bytea NOT NULL UNIQUE,         -- SHA-256 of the secret in the link
  deck_id    uuid NOT NULL,
  role       smallint NOT NULL,
  email      text,                          -- set: single use, only for this address
  created_by uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  expires_at timestamptz NOT NULL,
  used_at    timestamptz
);
CREATE INDEX invites_deck ON invites (deck_id);

CREATE TABLE publications (
  deck_id      uuid PRIMARY KEY,
  slug         text NOT NULL UNIQUE,
  listed       boolean NOT NULL,            -- unpublishing keeps followers
  published_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE subscriptions (
  provider   text NOT NULL,                 -- dawl's provider name
  ref        text NOT NULL,
  user_id    uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  product    text NOT NULL,
  status     text NOT NULL,                 -- dawl's status
  period_end timestamptz,
  auto_renew boolean NOT NULL,
  sandbox    boolean NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (provider, ref)
);
CREATE INDEX subscriptions_user ON subscriptions (user_id);

CREATE TABLE media (
  hash        bytea PRIMARY KEY,            -- SHA-256 of the bytes
  size        integer NOT NULL,
  mime        text NOT NULL,
  uploaded_by uuid REFERENCES users ON DELETE SET NULL,
  verified    boolean NOT NULL DEFAULT false,
  created_at  timestamptz NOT NULL DEFAULT now()
);

-- Synced tables (the sync server writes these) ------------------------------------------------------

CREATE TABLE counters (
  owner_id   uuid PRIMARY KEY REFERENCES users ON DELETE CASCADE,
  seq        bigint NOT NULL DEFAULT 0,     -- allocated under this row's lock, so pulls never skip rows
  purged_seq bigint NOT NULL DEFAULT 0      -- streams with an older cursor must start again
);

CREATE TABLE decks (
  id              uuid PRIMARY KEY,
  owner_id        uuid REFERENCES users ON DELETE SET NULL,   -- NULL: the author removed it
  parent_id       uuid,
  name            text NOT NULL,
  position        text NOT NULL,
  icon            text,
  color           smallint NOT NULL DEFAULT 0,
  description     text,
  language        text,
  new_per_day     smallint NOT NULL DEFAULT 20,
  reviews_per_day integer NOT NULL DEFAULT 200,
  retention       smallint NOT NULL DEFAULT 90,
  gated           boolean NOT NULL DEFAULT false,
  unlock_at       smallint NOT NULL DEFAULT 100,
  v               bigint NOT NULL,
  seq             bigint NOT NULL,
  deleted_at      bigint
);
CREATE INDEX decks_owner_seq ON decks (owner_id, seq);
CREATE INDEX decks_parent ON decks (parent_id);

CREATE TABLE templates (
  id         uuid PRIMARY KEY,
  owner_id   uuid REFERENCES users ON DELETE SET NULL,
  name       text NOT NULL,
  kind       smallint NOT NULL,             -- 1 Rondo, 2 Anki HTML
  def        jsonb NOT NULL,
  v          bigint NOT NULL,
  seq        bigint NOT NULL,
  deleted_at bigint
);
CREATE INDEX templates_owner_seq ON templates (owner_id, seq);

CREATE TABLE notes (
  id          uuid PRIMARY KEY,
  owner_id    uuid REFERENCES users ON DELETE SET NULL,      -- always its deck's owner
  deck_id     uuid NOT NULL,
  template_id uuid NOT NULL,
  fields      jsonb NOT NULL,
  tags        text NOT NULL DEFAULT '',
  search_text text NOT NULL DEFAULT '',     -- written by :core, never sent to devices
  v           bigint NOT NULL,
  seq         bigint NOT NULL,
  deleted_at  bigint
);
CREATE INDEX notes_owner_seq ON notes (owner_id, seq);
CREATE INDEX notes_deck_seq ON notes (deck_id, seq);
CREATE INDEX notes_template ON notes (template_id);

CREATE TABLE note_media (
  note_id uuid NOT NULL,
  hash    bytea NOT NULL,
  PRIMARY KEY (note_id, hash)
);
CREATE INDEX note_media_hash ON note_media (hash);

CREATE TABLE events (
  id          uuid PRIMARY KEY,
  user_id     uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  subject_id  uuid NOT NULL,                -- a note; a deck for unlock; an event for void
  card        smallint NOT NULL DEFAULT 0,
  kind        smallint NOT NULL,
  value       smallint,
  at          bigint NOT NULL,
  duration_ms integer,
  due         bigint,
  stability   real,
  difficulty  real,
  reps        smallint,
  lapses      smallint,
  seq         bigint NOT NULL
);
CREATE INDEX events_user_seq ON events (user_id, seq);
CREATE INDEX events_user_card ON events (user_id, subject_id, card, at);

CREATE TABLE settings (
  user_id      uuid PRIMARY KEY REFERENCES users ON DELETE CASCADE,
  grading      smallint NOT NULL DEFAULT 4,
  theme        smallint NOT NULL DEFAULT 0,
  text_size    smallint NOT NULL DEFAULT 100,
  timezone     text,
  fsrs_weights text,
  v            bigint NOT NULL,
  seq          bigint NOT NULL
);

-- +goose Down
DROP TABLE settings, events, note_media, notes, templates, decks, counters,
  media, subscriptions, publications, invites, shares, users;
