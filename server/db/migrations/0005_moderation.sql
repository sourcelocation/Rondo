-- +goose Up
-- Moderation. Restrictions and bans are derived from staff_actions into users, removed decks into
-- publications, IP restrictions into network_restrictions; undoing an entry derives them again.
-- Where people come from is kept per address (for 90 days) to find accounts that belong together.

ALTER TABLE users
  ADD COLUMN restricted_until timestamptz,    -- no publishing, inviting, names, profile or Discover until then
  ADD COLUMN banned_until     timestamptz,    -- no signing in until then (9999: for good)
  ADD COLUMN email_key        text;           -- keyed hash of the normalized email: who shares an address
CREATE INDEX users_email_key ON users (email_key) WHERE email_key IS NOT NULL;

ALTER TABLE publications
  ADD COLUMN removed_at timestamptz,          -- taken off Discover by staff; it can't be listed again
  ADD COLUMN removed_by uuid;                 -- the staff_actions entry

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

CREATE TABLE network_restrictions (
  action_id uuid PRIMARY KEY REFERENCES staff_actions,
  range     cidr NOT NULL,
  until     timestamptz
);
CREATE INDEX network_restrictions_range ON network_restrictions USING gist (range inet_ops);

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX names_similar ON names USING gin (value gin_trgm_ops);

-- +goose Down
DROP INDEX names_similar;
DROP TABLE network_restrictions, user_ips;
ALTER TABLE publications DROP COLUMN removed_at, DROP COLUMN removed_by;
ALTER TABLE users DROP COLUMN restricted_until, DROP COLUMN banned_until, DROP COLUMN email_key;
