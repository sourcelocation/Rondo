-- +goose Up
-- Notes have no tags: decks and Browse's filters group notes instead.
ALTER TABLE notes DROP COLUMN tags;

-- +goose Down
ALTER TABLE notes ADD COLUMN tags text NOT NULL DEFAULT '';
