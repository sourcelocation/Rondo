-- name: InsertAction :one
INSERT INTO staff_actions (id, actor_id, kind, user_id, deck_id, reason, note, until, data, case_id)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) RETURNING *;

-- name: Action :one
SELECT * FROM staff_actions WHERE id = $1;

-- name: MarkUndone :execrows
UPDATE staff_actions SET undone_at = now(), undone_by = $2, undo_reason = $3 WHERE id = $1 AND undone_at IS NULL;

-- name: CountRecentActions :one
-- What an actor did lately, for the hourly limits.
SELECT count(*) FROM staff_actions WHERE actor_id = $1 AND kind = $2 AND created_at > $3;

-- name: ActionsSince :many
SELECT * FROM staff_actions WHERE actor_id = $1 AND created_at >= $2 AND undone_at IS NULL ORDER BY id DESC;

-- name: ActionLog :many
SELECT * FROM staff_actions
WHERE (sqlc.narg(actor)::uuid IS NULL OR actor_id = sqlc.narg(actor))
  AND (sqlc.narg(target)::uuid IS NULL OR user_id = sqlc.narg(target))
  AND (sqlc.narg(deck)::uuid IS NULL OR deck_id = sqlc.narg(deck))
  AND (sqlc.narg(kind)::text IS NULL OR kind = sqlc.narg(kind))
  AND (sqlc.narg(before)::uuid IS NULL OR id < sqlc.narg(before))
ORDER BY id DESC LIMIT 51;

-- name: ClearRoles :exec
DELETE FROM roles WHERE user_id = $1;

-- name: DeriveRoles :exec
-- A role is held when the newest entry about it, not undone, grants it.
INSERT INTO roles (user_id, role, action_id)
SELECT $1, latest.role, latest.id FROM (
  SELECT DISTINCT ON (a.data->>'role') a.id, a.kind, a.data->>'role' AS role
  FROM staff_actions a
  WHERE a.user_id = $1 AND a.kind IN ('role.grant', 'role.revoke') AND a.undone_at IS NULL
  ORDER BY a.data->>'role', a.id DESC
) latest WHERE latest.kind = 'role.grant';

-- name: RolesOf :many
SELECT role FROM roles WHERE user_id = $1 ORDER BY role;

-- name: Team :many
SELECT user_id, array_agg(role ORDER BY role)::text[] AS roles FROM roles GROUP BY user_id;

-- name: Frozen :one
-- Frozen by the hourly limits until someone undoes the freeze.
SELECT EXISTS (SELECT 1 FROM staff_actions WHERE user_id = $1 AND kind = 'staff.freeze' AND undone_at IS NULL);

-- name: UserRefs :many
SELECT id, name, username, flag FROM users WHERE id = ANY($1::uuid[]);

-- name: InsertNotification :exec
INSERT INTO notifications (id, user_id, kind, presentation, title, body, link_label, link_url)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8);

-- name: UnseenNotifications :many
SELECT * FROM notifications WHERE user_id = $1 AND seen_at IS NULL AND presentation > 0 ORDER BY id LIMIT 20;

-- name: MarkSeen :execrows
UPDATE notifications SET seen_at = now() WHERE id = $1 AND user_id = $2 AND seen_at IS NULL;

-- name: CountRecentUndos :one
SELECT count(*) FROM staff_actions WHERE undone_by = $1 AND undone_at > $2;
