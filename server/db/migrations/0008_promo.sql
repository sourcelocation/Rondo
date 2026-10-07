-- +goose Up
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

-- +goose Down
DROP TABLE promo_codes;
