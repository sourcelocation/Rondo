-- +goose Up
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

-- +goose Down
DROP TABLE second_factor_resets, email_changes;
