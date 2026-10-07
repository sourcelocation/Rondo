-- name: PutSummary :exec
INSERT INTO summaries (user_id, due, learned) VALUES ($1, $2, $3)
ON CONFLICT (user_id) DO UPDATE SET due = EXCLUDED.due, learned = EXCLUDED.learned, at = now();

-- name: Summary :one
SELECT * FROM summaries WHERE user_id = $1;

-- name: PutPushDevice :one
INSERT INTO push_devices (id, user_id, platform, endpoint, p256dh, auth, reminders, local_until)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
ON CONFLICT (endpoint) DO UPDATE SET user_id = EXCLUDED.user_id, p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth,
  reminders = EXCLUDED.reminders, local_until = EXCLUDED.local_until
RETURNING *;

-- name: DeletePushDevice :exec
DELETE FROM push_devices WHERE id = $1 AND user_id = $2;

-- name: ForgetPushDevice :exec
DELETE FROM push_devices WHERE id = $1;

-- name: PushOK :exec
UPDATE push_devices SET last_ok_at = now() WHERE id = $1;

-- name: PushDevices :many
SELECT * FROM push_devices WHERE user_id = $1;

-- name: Reminding :many
-- Everyone with reminders on, and what the server knows to decide whether to remind them now.
SELECT s.user_id, s.reminder_at, coalesce(s.reminder_email, false)::boolean AS email, s.timezone,
  r.last_sent_at, r.last_sent_on, coalesce(r.ignored, 0)::smallint AS ignored, r.paused_at,
  coalesce((SELECT max(e.at) FROM events e WHERE e.user_id = s.user_id AND e.kind = 1), 0)::bigint AS last_review
FROM settings s LEFT JOIN reminder_state r ON r.user_id = s.user_id
WHERE s.reminder_at IS NOT NULL;

-- name: PutReminderState :exec
INSERT INTO reminder_state (user_id, last_sent_at, last_sent_on, ignored, paused_at) VALUES ($1, $2, $3, $4, $5)
ON CONFLICT (user_id) DO UPDATE SET last_sent_at = EXCLUDED.last_sent_at, last_sent_on = EXCLUDED.last_sent_on,
  ignored = EXCLUDED.ignored, paused_at = EXCLUDED.paused_at;
