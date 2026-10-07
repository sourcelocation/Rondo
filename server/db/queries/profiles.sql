-- name: UserByUsername :one
SELECT * FROM users WHERE username = $1;

-- name: SetProfile :one
UPDATE users SET username = $2, username_changed_at = $3, name = $4, flag = $5, activity_hidden = $6,
  profile_confirmed_at = coalesce(profile_confirmed_at, $7)
WHERE id = $1 RETURNING *;

-- name: AddName :exec
INSERT INTO names (id, user_id, kind, value, action_id) VALUES ($1, $2, $3, $4, $5);

-- name: UsernameHeld :one
-- Someone else's username until less than 30 days ago stays theirs.
SELECT EXISTS (
  SELECT 1 FROM names n WHERE n.kind = 1 AND n.value = $1 AND n.user_id <> $2 AND NOT n.undone
    AND EXISTS (SELECT 1 FROM names m WHERE m.user_id = n.user_id AND m.kind = 1 AND NOT m.undone
                AND m.created_at > n.created_at AND m.created_at > now() - interval '30 days')
);

-- name: MovedUsername :one
-- Where an old username went: its last holder's username now, when they changed it in the last 30 days.
SELECT u.username FROM names n JOIN users u ON u.id = n.user_id
WHERE n.kind = 1 AND n.value = $1 AND NOT n.undone AND u.username <> $1
  AND EXISTS (SELECT 1 FROM names m WHERE m.user_id = n.user_id AND m.kind = 1 AND NOT m.undone
              AND m.created_at > n.created_at AND m.created_at > now() - interval '30 days')
ORDER BY n.created_at DESC LIMIT 1;

-- name: ReviewDays :many
-- Days with reviews, in the learner's time zone with days starting at 4 am, without undone answers.
SELECT (to_timestamp(e.at / 1000.0) AT TIME ZONE sqlc.arg(zone)::text - interval '4 hours')::date AS day,
  count(*)::int AS reviews, coalesce(sum(e.duration_ms), 0)::bigint AS ms
FROM events e
WHERE e.user_id = sqlc.arg(user_id) AND e.kind = 1
  AND NOT EXISTS (SELECT 1 FROM events v WHERE v.user_id = e.user_id AND v.kind = 8 AND v.subject_id = e.id)
GROUP BY 1 ORDER BY 1;

-- name: Timezone :one
SELECT timezone FROM settings WHERE user_id = $1;
