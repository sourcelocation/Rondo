-- name: StartEmailChange :exec
INSERT INTO email_changes (user_id, email, code_hash, expires_at) VALUES ($1, $2, $3, $4)
ON CONFLICT (user_id) DO UPDATE SET email = EXCLUDED.email, code_hash = EXCLUDED.code_hash, tries = 0, expires_at = EXCLUDED.expires_at;

-- name: EmailChange :one
SELECT * FROM email_changes WHERE user_id = $1;

-- name: TryEmailChange :exec
UPDATE email_changes SET tries = tries + 1 WHERE user_id = $1;

-- name: EndEmailChange :exec
DELETE FROM email_changes WHERE user_id = $1;

-- name: StartReset :exec
INSERT INTO second_factor_resets (user_id, code_hash, expires_at) VALUES ($1, $2, $3)
ON CONFLICT (user_id) DO UPDATE SET code_hash = EXCLUDED.code_hash, tries = 0, expires_at = EXCLUDED.expires_at
WHERE second_factor_resets.due_at IS NULL;

-- name: Reset :one
SELECT * FROM second_factor_resets WHERE user_id = $1;

-- name: TryReset :exec
UPDATE second_factor_resets SET tries = tries + 1 WHERE user_id = $1;

-- name: ScheduleReset :exec
UPDATE second_factor_resets SET due_at = $2 WHERE user_id = $1;

-- name: EndReset :exec
DELETE FROM second_factor_resets WHERE user_id = $1;
