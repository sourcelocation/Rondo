-- +goose Up
-- Reminders, which people turn on: when (a synced setting, so every device knows), on which devices
-- (push_devices), and from what each device last counted as due (summaries). Devices that show
-- their own reminders say until when (local_until); the server reminds the rest, and email.

ALTER TABLE settings
  ADD COLUMN reminder_at    smallint,                 -- minutes after local midnight; NULL: off
  ADD COLUMN reminder_email boolean;                  -- also by email (NULL: no)

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

-- +goose Down
DROP TABLE reminder_state, summaries, push_devices;
ALTER TABLE settings DROP COLUMN reminder_at, DROP COLUMN reminder_email;
