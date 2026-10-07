-- name: DeriveStanding :one
-- Restricted and banned until the latest end of those entries since the last lift, none undone.
WITH lift AS (
  SELECT max(created_at) AS at FROM staff_actions WHERE user_id = $1 AND kind = 'user.lift' AND undone_at IS NULL
)
UPDATE users u SET
  restricted_until = (SELECT max(coalesce(a.until, '9999-12-31')) FROM staff_actions a, lift
                      WHERE a.user_id = $1 AND a.kind = 'user.restrict' AND a.undone_at IS NULL
                        AND (lift.at IS NULL OR a.created_at > lift.at)),
  banned_until = (SELECT max(coalesce(a.until, '9999-12-31')) FROM staff_actions a, lift
                  WHERE a.user_id = $1 AND a.kind = 'user.ban' AND a.undone_at IS NULL
                    AND (lift.at IS NULL OR a.created_at > lift.at))
WHERE u.id = $1
RETURNING restricted_until, banned_until;

-- name: Ladder :one
-- What someone was warned, restricted or banned for lately: the next step follows from it.
SELECT count(*) FROM staff_actions
WHERE user_id = $1 AND kind IN ('user.warn', 'user.restrict', 'user.ban', 'deck.remove') AND undone_at IS NULL
  AND created_at > now() - interval '180 days';

-- name: DerivePublication :exec
UPDATE publications p SET (removed_at, removed_by) = (
  SELECT a.created_at, a.id FROM staff_actions a
  WHERE a.deck_id = p.deck_id AND a.kind = 'deck.remove' AND a.undone_at IS NULL ORDER BY a.id DESC LIMIT 1
) WHERE p.deck_id = $1;

-- name: SharesOf :many
SELECT user_id, role FROM shares WHERE deck_id = $1;

-- name: DeleteShares :exec
DELETE FROM shares WHERE deck_id = $1;

-- name: CurrentName :one
-- The newest name of a kind that isn't undone, and whether staff set it.
SELECT value, (action_id IS NOT NULL)::boolean AS by_staff FROM names
WHERE user_id = $1 AND kind = $2 AND NOT undone ORDER BY created_at DESC LIMIT 1;

-- name: UndoNames :exec
UPDATE names SET undone = true WHERE action_id = $1;

-- name: SetNames :exec
UPDATE users SET username = $2, name = $3, username_changed_at = $4 WHERE id = $1;

-- name: DeriveNetwork :exec
DELETE FROM network_restrictions WHERE action_id = $1;

-- name: AddNetwork :exec
INSERT INTO network_restrictions (action_id, range, until) VALUES ($1, $2, $3);

-- name: NetworkRestricted :one
SELECT EXISTS (SELECT 1 FROM network_restrictions WHERE range >>= $1::inet AND (until IS NULL OR until > now()));

-- name: Seen :exec
-- Where someone is: every social action counts.
INSERT INTO user_ips (user_id, ip) VALUES ($1, $2)
ON CONFLICT (user_id, ip) DO UPDATE SET last_at = now(), uses = user_ips.uses + 1;

-- name: SeenHourly :exec
-- The same for opening the app, at most once an hour per address.
INSERT INTO user_ips (user_id, ip) VALUES ($1, $2)
ON CONFLICT (user_id, ip) DO UPDATE SET last_at = now(), uses = user_ips.uses + 1
WHERE user_ips.last_at < now() - interval '1 hour';

-- name: SetEmailKey :exec
UPDATE users SET email_key = $2 WHERE id = $1 AND email_key IS DISTINCT FROM $2;

-- name: ForgetOldIPs :exec
DELETE FROM user_ips WHERE last_at < now() - interval '90 days';

-- name: ForgetSeenNotifications :exec
DELETE FROM notifications WHERE seen_at < now() - interval '90 days' OR (presentation = 0 AND created_at < now() - interval '90 days');

-- name: IPsOf :many
SELECT ip::text AS ip, first_at, last_at, uses FROM user_ips WHERE user_id = $1 ORDER BY last_at DESC LIMIT 50;

-- name: NamesOf :many
SELECT kind, value, created_at, (action_id IS NOT NULL)::boolean AS by_staff, undone FROM names
WHERE user_id = $1 ORDER BY created_at DESC LIMIT 100;

-- name: RelatedByIP :many
-- Others seen at the same address (IPv6: the same /64) in the last 90 days.
SELECT other.user_id, count(*)::int AS shared FROM user_ips mine
JOIN user_ips other ON other.user_id <> mine.user_id AND (
  other.ip = mine.ip
  OR (family(mine.ip) = 6 AND family(other.ip) = 6 AND network(set_masklen(other.ip, 64)) = network(set_masklen(mine.ip, 64))))
WHERE mine.user_id = $1
GROUP BY other.user_id ORDER BY 2 DESC LIMIT 20;

-- name: RelatedByEmail :many
SELECT id FROM users WHERE email_key = $1 AND id <> $2 LIMIT 20;

-- name: RelatedByName :many
-- Others whose names, now or before, look like any of this person's (Rondo's own usernames aside).
SELECT DISTINCT other.user_id FROM names mine
JOIN names other ON other.user_id <> mine.user_id AND other.value % mine.value AND similarity(other.value, mine.value) > 0.6
WHERE mine.user_id = $1 AND mine.value IS NOT NULL AND length(mine.value) >= 4
  AND mine.value !~ '^user[0-9]+$' AND other.value !~ '^user[0-9]+$'
LIMIT 20;

-- name: SearchUsers :many
SELECT * FROM users
WHERE username LIKE lower(sqlc.arg(q)::text) || '%' OR name ILIKE '%' || sqlc.arg(q)::text || '%' OR id::text = sqlc.arg(q)::text
ORDER BY (username = lower(sqlc.arg(q)::text)) DESC, created_at DESC LIMIT 30;

-- name: UsersAt :many
SELECT DISTINCT u.* FROM users u JOIN user_ips i ON i.user_id = u.id WHERE i.ip <<= sqlc.arg(range)::cidr LIMIT 30;

-- name: UsersByID :many
SELECT * FROM users WHERE id = ANY($1::uuid[]);

-- name: RenewingSubscriptions :many
-- Someone's store subscriptions that will renew.
SELECT provider, ref FROM subscriptions WHERE user_id = $1 AND auto_renew AND status IN ('active', 'trialing', 'in_grace');

-- name: StoppedRenewals :many
-- Someone's subscriptions whose renewal a ban turned off.
SELECT * FROM subscriptions WHERE user_id = $1 AND renewal_stopped;

-- name: SetRenewalStopped :exec
UPDATE subscriptions SET renewal_stopped = $3 WHERE provider = $1 AND ref = $2;
