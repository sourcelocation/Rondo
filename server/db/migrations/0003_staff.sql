-- +goose Up
-- Staff and notifications. The owner comes from RONDO_OWNER_EMAIL and isn't stored. Every staff
-- change is an entry in staff_actions; what it decides (roles here, more in later migrations) is
-- derived from the entries not undone, so undoing one is marking it and deriving again.

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

-- +goose Down
DROP TABLE notifications, roles, staff_actions;
