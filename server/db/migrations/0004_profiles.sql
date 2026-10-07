-- +goose Up
-- Public identity: a unique username in addresses (user<number> until someone picks one), the
-- display name in any script, a flag, and a profile people confirm once. Every name anyone had is
-- kept in `names`: staff compare them, an old username stays its owner's for 30 days (and redirects),
-- and staff renames are derived from it, so they can be undone.

ALTER TABLE users
  ADD COLUMN username             text,
  ADD COLUMN username_changed_at  timestamptz,           -- last chosen change; the next waits 30 days
  ADD COLUMN flag                 text,                  -- ISO 3166 country code
  ADD COLUMN activity_hidden      boolean NOT NULL DEFAULT false,
  ADD COLUMN profile_confirmed_at timestamptz;

UPDATE users SET username = 'user' || (abs(hashtextextended(id::text, 0)) % 10000000000)::text;
ALTER TABLE users ALTER COLUMN username SET NOT NULL;
CREATE UNIQUE INDEX users_username ON users (username);

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

INSERT INTO names (id, user_id, kind, value, created_at)
SELECT gen_random_uuid(), id, 1, username, created_at FROM users;
INSERT INTO names (id, user_id, kind, value, created_at)
SELECT gen_random_uuid(), id, 2, name, created_at FROM users WHERE name IS NOT NULL;

-- Profiles read reviews by day.
CREATE INDEX events_user_reviews ON events (user_id, at) WHERE kind = 1;

-- +goose Down
DROP INDEX events_user_reviews;
DROP TABLE names;
ALTER TABLE users DROP COLUMN username, DROP COLUMN username_changed_at, DROP COLUMN flag,
  DROP COLUMN activity_hidden, DROP COLUMN profile_confirmed_at;
