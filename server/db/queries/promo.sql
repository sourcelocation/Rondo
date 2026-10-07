-- name: AddPromoCode :exec
INSERT INTO promo_codes (code, batch_id) VALUES ($1, $2);

-- name: PromoCode :one
SELECT c.code, b.until AS redeem_by, b.data, (b.undone_at IS NOT NULL)::boolean AS batch_undone,
  (c.revoked_by IS NOT NULL AND r.undone_at IS NULL)::boolean AS revoked
FROM promo_codes c JOIN staff_actions b ON b.id = c.batch_id LEFT JOIN staff_actions r ON r.id = c.revoked_by
WHERE c.code = $1;

-- name: RevokeCode :exec
UPDATE promo_codes SET revoked_by = $2 WHERE code = $1;

-- name: UnrevokeCode :exec
UPDATE promo_codes SET revoked_by = NULL WHERE code = $1 AND revoked_by = $2;

-- name: PromoBatches :many
SELECT b.*, (SELECT count(*) FROM promo_codes c WHERE c.batch_id = b.id)::int AS codes,
  (SELECT count(*) FROM promo_codes c JOIN subscriptions s ON s.provider = 'promo' AND s.ref = c.code WHERE c.batch_id = b.id)::int AS used
FROM staff_actions b WHERE b.kind = 'promo.batch' ORDER BY b.id DESC LIMIT 100;

-- name: BatchCodes :many
SELECT c.code, s.user_id AS used_by, s.updated_at AS used_at, (c.revoked_by IS NOT NULL AND r.undone_at IS NULL)::boolean AS revoked
FROM promo_codes c
LEFT JOIN subscriptions s ON s.provider = 'promo' AND s.ref = c.code
LEFT JOIN staff_actions r ON r.id = c.revoked_by
WHERE c.batch_id = $1 ORDER BY c.code;

-- name: Redemption :one
SELECT * FROM subscriptions WHERE provider = 'promo' AND ref = $1;

-- name: SetSubscriptionStatus :exec
UPDATE subscriptions SET status = $3, updated_at = now() WHERE provider = $1 AND ref = $2;

-- name: AddGrant :execrows
-- Pro that isn't bought: from staff ("grant") or a code ("promo").
INSERT INTO subscriptions (provider, ref, user_id, product, status, period_end, auto_renew, sandbox)
VALUES ($1, $2, $3, 'pro', 'active', $4, false, false) ON CONFLICT DO NOTHING;

-- name: Renewing :one
-- Whether someone pays for Pro through a store and it renews.
SELECT EXISTS (
  SELECT 1 FROM subscriptions WHERE user_id = $1 AND provider NOT IN ('promo', 'grant') AND auto_renew
    AND status IN ('active', 'trialing', 'in_grace')
);
