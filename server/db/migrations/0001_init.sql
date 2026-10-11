-- +goose Up
-- The whole schema. Go writes the first group of tables, the sync server the second; neither writes
-- the other's. Synced tables have no foreign keys between each other (pushes arrive in any order and
-- :core enforces references); only `users` is referenced, because account deletion relies on it.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- People ---------------------------------------------------------------------------------------------

-- Public identity: a unique username in addresses (user<number> until someone picks one), the
-- display name in any script, a flag, and a profile people confirm once.
CREATE TABLE users (
  id                   uuid PRIMARY KEY,         -- the Kratos identity id
  username             text NOT NULL,
  username_changed_at  timestamptz,              -- last chosen change; the next waits 30 days
  name                 text,                     -- display name, required before sharing or publishing
  flag                 text,                     -- ISO 3166 country code
  activity_hidden      boolean NOT NULL DEFAULT false,
  profile_confirmed_at timestamptz,
  pro_until            timestamptz,              -- written from dawl's state, read by the sync server
  stripe_customer      text,
  restricted_until     timestamptz,              -- no publishing, inviting, names, profile or Discover until then
  banned_until         timestamptz,              -- no signing in until then (9999: for good)
  email_key            text,                     -- keyed hash of the normalized email: who shares an address
  created_at           timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_username ON users (username);
CREATE INDEX users_email_key ON users (email_key) WHERE email_key IS NOT NULL;

-- Staff. The owner comes from RONDO_OWNER_EMAIL and isn't stored. Every staff change is an entry
-- here; what it decides (roles, names, restrictions, bans, removed decks, promo codes) is derived
-- from the entries not undone, so undoing one is marking it and deriving again.
CREATE TABLE staff_actions (
  id          uuid PRIMARY KEY,                               -- UUIDv7
  actor_id    uuid REFERENCES users ON DELETE SET NULL,       -- NULL: Rondo itself, or someone who left
  kind        text NOT NULL,                                  -- role.grant, user.ban, …
  user_id     uuid REFERENCES users ON DELETE SET NULL,       -- the person it's about
  deck_id     uuid,                                           -- the deck it's about
  reason      text,
  note        text,
  until       timestamptz,
  data        jsonb NOT NULL DEFAULT '{}',                    -- the kind's own values, as strings
  case_id     uuid,
  created_at  timestamptz NOT NULL DEFAULT now(),
  undone_at   timestamptz,
  undone_by   uuid REFERENCES users ON DELETE SET NULL,
  undo_reason text
);
CREATE INDEX staff_actions_user ON staff_actions (user_id, id DESC);
CREATE INDEX staff_actions_actor ON staff_actions (actor_id, id DESC);
CREATE INDEX staff_actions_deck ON staff_actions (deck_id, id DESC) WHERE deck_id IS NOT NULL;

-- Derived from role.grant and role.revoke entries.
CREATE TABLE roles (
  user_id   uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  role      text NOT NULL,                                    -- admin, moderator, support, curator
  action_id uuid NOT NULL REFERENCES staff_actions,           -- the grant
  PRIMARY KEY (user_id, role)
);

-- Every name anyone had: staff compare them, an old username stays its owner's for 30 days (and
-- redirects), and staff renames are derived from it, so they can be undone.
CREATE TABLE names (
  id         uuid PRIMARY KEY,
  user_id    uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  kind       smallint NOT NULL,                                   -- 1 username, 2 display name
  value      text,                                                -- NULL: a display name cleared
  action_id  uuid REFERENCES staff_actions,                       -- set when staff changed it
  created_at timestamptz NOT NULL DEFAULT now(),
  undone     boolean NOT NULL DEFAULT false
);
CREATE INDEX names_user ON names (user_id, kind, created_at DESC);
CREATE INDEX names_username ON names (value) WHERE kind = 1;
CREATE INDEX names_similar ON names USING gin (value gin_trgm_ops);

CREATE TABLE notifications (
  id           uuid PRIMARY KEY,                              -- UUIDv7
  user_id      uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  kind         text NOT NULL,
  presentation smallint NOT NULL,                             -- 0 elsewhere only (mail, push), 1 toast, 2 modal
  title        text NOT NULL,
  body         text NOT NULL,
  link_label   text,
  link_url     text,
  created_at   timestamptz NOT NULL DEFAULT now(),
  seen_at      timestamptz
);
CREATE INDEX notifications_unseen ON notifications (user_id, id) WHERE seen_at IS NULL AND presentation > 0;

-- Account security beyond Kratos's own flows: moving to a new email once a code sent there is
-- entered, and removing a lost second step a week after its owner asked (cancelled by signing in).
CREATE TABLE email_changes (
  user_id    uuid PRIMARY KEY REFERENCES users ON DELETE CASCADE,
  email      text NOT NULL,
  code_hash  bytea NOT NULL,
  tries      smallint NOT NULL DEFAULT 0,
  expires_at timestamptz NOT NULL
);

CREATE TABLE second_factor_resets (
  user_id      uuid PRIMARY KEY REFERENCES users ON DELETE CASCADE,
  code_hash    bytea NOT NULL,
  tries        smallint NOT NULL DEFAULT 0,
  expires_at   timestamptz NOT NULL,              -- for entering the code
  due_at       timestamptz                        -- set once confirmed: when the second step goes
);

-- Moderation -----------------------------------------------------------------------------------------

-- Where people come from, kept per address (for 90 days) to find accounts that belong together.
CREATE TABLE user_ips (
  user_id  uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  ip       inet NOT NULL,
  first_at timestamptz NOT NULL DEFAULT now(),
  last_at  timestamptz NOT NULL DEFAULT now(),
  uses     integer NOT NULL DEFAULT 1,
  PRIMARY KEY (user_id, ip)
);
CREATE INDEX user_ips_ip ON user_ips USING gist (ip inet_ops);
CREATE INDEX user_ips_last ON user_ips (last_at);

-- Derived from IP restrictions in staff_actions.
CREATE TABLE network_restrictions (
  action_id uuid PRIMARY KEY REFERENCES staff_actions,
  range     cidr NOT NULL,
  until     timestamptz
);
CREATE INDEX network_restrictions_range ON network_restrictions USING gist (range inet_ops);

-- A case gathers what points at one deck or person (reports), or at a day that stands out (a wave),
-- or at something a rule flags. Reports weigh what their reporter's record is worth; a case reaches
-- staff once its weight crosses a threshold. Resolving is a staff action (acting with the case, or
-- case.dismiss), so the case's outcome is derived and can be undone.
CREATE TABLE cases (
  id          uuid PRIMARY KEY,                          -- UUIDv7
  kind        smallint NOT NULL,                         -- 1 reports, 2 a wave, 3 a rule's flag
  target_kind smallint,                                  -- 1 a deck, 2 a person
  target_id   uuid,
  lane        text NOT NULL,                             -- illegal, safety, spam, other, waves, flags
  score       real NOT NULL DEFAULT 0,
  opened_at   timestamptz,                               -- crossed the threshold; NULL: not shown to staff
  resolved_at timestamptz,
  resolved_by uuid REFERENCES users ON DELETE SET NULL,
  outcome     smallint,                                  -- 1 acted on, 2 dismissed
  data        jsonb NOT NULL DEFAULT '{}',               -- a wave's or a flag's details
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX cases_target ON cases (target_kind, target_id) WHERE kind = 1 AND resolved_at IS NULL;
CREATE INDEX cases_queue ON cases (opened_at) WHERE opened_at IS NOT NULL AND resolved_at IS NULL;

CREATE TABLE reports (
  id          uuid PRIMARY KEY,                          -- UUIDv7
  reporter_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  case_id     uuid NOT NULL REFERENCES cases ON DELETE CASCADE,
  reason      text NOT NULL,
  law         text,                                      -- for illegal content: which law
  note        text,
  weight      real NOT NULL,                             -- what it counted when it was made
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (reporter_id, case_id)
);
CREATE INDEX reports_reporter ON reports (reporter_id, created_at);
CREATE INDEX reports_case ON reports (case_id);

-- Sharing and Discover -------------------------------------------------------------------------------

CREATE TABLE shares (
  deck_id    uuid NOT NULL,                 -- the lent deck; decks belong to the sync server
  user_id    uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  role       smallint NOT NULL,             -- 1 viewer, 2 editor
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (deck_id, user_id)
);
CREATE INDEX shares_user ON shares (user_id);
CREATE INDEX shares_created ON shares (created_at);

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

-- Discover reads only this table: each listing keeps a copy of its deck's name, language and author,
-- and counts (followers, notes, follows in the last week for Hot), refreshed every ten minutes and
-- when something about it changes. Each sort reads its own index.
CREATE TABLE publications (
  deck_id      uuid PRIMARY KEY,
  slug         text NOT NULL UNIQUE,
  listed       boolean NOT NULL,            -- unpublishing keeps followers
  published_at timestamptz NOT NULL DEFAULT now(),
  removed_at   timestamptz,                 -- taken off Discover by staff; it can't be listed again
  removed_by   uuid,                        -- the staff_actions entry
  name         text,
  language     text,
  owner_id     uuid,
  gone         boolean NOT NULL DEFAULT false,   -- the deck was deleted, or has no author
  followers    integer NOT NULL DEFAULT 0,
  notes        integer NOT NULL DEFAULT 0,
  hot          integer NOT NULL DEFAULT 0,       -- follows in the last 7 days
  refreshed_at timestamptz
);
CREATE INDEX publications_top ON publications (followers DESC, published_at DESC) WHERE listed AND removed_at IS NULL AND NOT gone;
CREATE INDEX publications_new ON publications (published_at DESC) WHERE listed AND removed_at IS NULL AND NOT gone;
CREATE INDEX publications_hot ON publications (hot DESC, followers DESC) WHERE listed AND removed_at IS NULL AND NOT gone;
CREATE INDEX publications_name ON publications USING gin (name gin_trgm_ops);

-- Billing --------------------------------------------------------------------------------------------

CREATE TABLE subscriptions (
  provider        text NOT NULL,            -- dawl's provider name
  ref             text NOT NULL,
  user_id         uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  product         text NOT NULL,
  status          text NOT NULL,            -- dawl's status
  period_end      timestamptz,
  auto_renew      boolean NOT NULL,
  sandbox         boolean NOT NULL,
  -- A ban turns off the renewal of store subscriptions that allow it from the server (dawl's
  -- Renewer); lifting or undoing the ban turns back on exactly the ones it turned off.
  renewal_stopped boolean NOT NULL DEFAULT false,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (provider, ref)
);
CREATE INDEX subscriptions_user ON subscriptions (user_id);

-- Promo codes come in batches, each a promo.batch staff action (its note, how many days of Pro, and
-- until when codes can be redeemed live in the entry). A code is single-use: redeeming it is a
-- subscriptions row from the provider "promo" with the code as its ref. A code is revoked while its
-- promo.revoke entry isn't undone; a whole batch while its entry isn't undone.
CREATE TABLE promo_codes (
  code       text PRIMARY KEY,                               -- 12 characters, Crockford's base 32
  batch_id   uuid NOT NULL REFERENCES staff_actions,
  revoked_by uuid REFERENCES staff_actions
);
CREATE INDEX promo_codes_batch ON promo_codes (batch_id);

-- Media and reminders --------------------------------------------------------------------------------

CREATE TABLE media (
  hash        bytea PRIMARY KEY,            -- SHA-256 of the bytes
  size        integer NOT NULL,
  mime        text NOT NULL,
  uploaded_by uuid REFERENCES users ON DELETE SET NULL,
  verified    boolean NOT NULL DEFAULT false,
  created_at  timestamptz NOT NULL DEFAULT now()
);

-- Reminders, which people turn on: when (a synced setting, so every device knows), on which devices
-- (push_devices), and from what each device last counted as due (summaries). Devices that show
-- their own reminders say until when (local_until); the server reminds the rest, and email.
CREATE TABLE push_devices (
  id          uuid PRIMARY KEY,
  user_id     uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  platform    smallint NOT NULL,                      -- 1 web push, 2 APNs, 3 FCM
  endpoint    text NOT NULL UNIQUE,                   -- the push service's address, or the device token
  p256dh      text,                                   -- web push keys
  auth        text,
  reminders   boolean NOT NULL DEFAULT false,         -- wants reminders from the server
  local_until timestamptz,                            -- shows its own reminders until then
  created_at  timestamptz NOT NULL DEFAULT now(),
  last_ok_at  timestamptz
);
CREATE INDEX push_devices_user ON push_devices (user_id);

CREATE TABLE summaries (
  user_id uuid PRIMARY KEY REFERENCES users ON DELETE CASCADE,
  due     integer[] NOT NULL,                         -- cards due on each of the next 7 days, today first
  learned integer NOT NULL,
  at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE reminder_state (
  user_id      uuid PRIMARY KEY REFERENCES users ON DELETE CASCADE,
  last_sent_at timestamptz,
  last_sent_on date,                                  -- the learner's day it was sent on
  ignored      smallint NOT NULL DEFAULT 0,           -- reminders in a row with no studying after
  paused_at    timestamptz                            -- after 7 ignored; studying again resumes them
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

-- Browse's filters, saved and studied like a deck. A smart deck is its owner's alone, so it goes
-- with the account and is never lent.
CREATE TABLE smart_decks (
  id              uuid PRIMARY KEY,
  owner_id        uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  name            text NOT NULL,
  position        text NOT NULL,
  icon            text,
  color           smallint NOT NULL DEFAULT 0,
  filter          jsonb NOT NULL,
  new_per_day     smallint NOT NULL DEFAULT 20,
  reviews_per_day integer NOT NULL DEFAULT 200,
  v               bigint NOT NULL,
  seq             bigint NOT NULL,
  deleted_at      bigint
);
CREATE INDEX smart_decks_owner_seq ON smart_decks (owner_id, seq);

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
  tags        text NOT NULL DEFAULT '',     -- space-separated, as :core's Tags keeps them
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
CREATE INDEX events_user_reviews ON events (user_id, at) WHERE kind = 1;   -- profiles read reviews by day

CREATE TABLE settings (
  user_id        uuid PRIMARY KEY REFERENCES users ON DELETE CASCADE,
  grading        smallint NOT NULL DEFAULT 4,
  theme          smallint NOT NULL DEFAULT 0,
  text_size      smallint NOT NULL DEFAULT 100,
  timezone       text,
  fsrs_weights   text,
  reminder_at    smallint,                  -- minutes after local midnight; NULL: off
  v              bigint NOT NULL,
  seq            bigint NOT NULL
);

-- +goose Down
DROP TABLE settings, events, note_media, notes, templates, smart_decks, decks, counters,
  reminder_state, summaries, push_devices, media, promo_codes, subscriptions, publications, invites,
  shares, reports, cases, network_restrictions, user_ips, second_factor_resets, email_changes,
  notifications, names, roles, staff_actions, users;
