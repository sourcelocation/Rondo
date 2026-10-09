-- +goose Up
-- Notes have tags again (big Anki decks are organized by them), and learners keep smart decks:
-- Browse's filters, saved and studied like a deck. A smart deck is its owner's alone, so it goes
-- with the account and is never lent.

ALTER TABLE notes ADD COLUMN tags text NOT NULL DEFAULT '';  -- space-separated, as :core's Tags keeps them

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

-- +goose Down
DROP TABLE smart_decks;
ALTER TABLE notes DROP COLUMN tags;
