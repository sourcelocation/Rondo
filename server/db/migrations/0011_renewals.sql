-- +goose Up
-- A ban turns off the renewal of store subscriptions that allow it from the server (dawl's
-- Renewer); lifting or undoing the ban turns back on exactly the ones it turned off.

ALTER TABLE subscriptions ADD COLUMN renewal_stopped boolean NOT NULL DEFAULT false;

-- +goose Down
ALTER TABLE subscriptions DROP COLUMN renewal_stopped;
