-- name: ReporterRecord :one
-- How a reporter's earlier reports went: how many led to action, of those resolved.
SELECT count(*) FILTER (WHERE c.outcome = 1)::int AS upheld, count(*) FILTER (WHERE c.resolved_at IS NOT NULL)::int AS resolved
FROM reports r JOIN cases c ON c.id = r.case_id WHERE r.reporter_id = $1;

-- name: ReportsLately :one
SELECT count(*)::int FROM reports WHERE reporter_id = $1 AND created_at > now() - interval '24 hours';

-- name: ReportsCase :one
-- The unresolved reports case about a target, made when there's none.
WITH made AS (
  INSERT INTO cases (id, kind, target_kind, target_id, lane) VALUES ($1, 1, $2, $3, 'other')
  ON CONFLICT (target_kind, target_id) WHERE kind = 1 AND resolved_at IS NULL DO NOTHING
  RETURNING *
)
SELECT * FROM made
UNION ALL
SELECT * FROM cases WHERE kind = 1 AND target_kind = $2 AND target_id = $3 AND resolved_at IS NULL
LIMIT 1;

-- name: AddReport :execrows
INSERT INTO reports (id, reporter_id, case_id, reason, law, note, weight) VALUES ($1, $2, $3, $4, $5, $6, $7)
ON CONFLICT (reporter_id, case_id) DO NOTHING;

-- name: ScoreCase :one
-- A case's weight and lane from its reports; it opens to staff past the threshold, or at once for
-- illegal content.
UPDATE cases c SET score = s.total, lane = s.lane,
  opened_at = coalesce(c.opened_at, CASE WHEN s.total >= sqlc.arg(threshold)::real OR s.lane = 'illegal' THEN now() END)
FROM (
  SELECT sum(weight)::real AS total,
    (ARRAY['illegal', 'safety', 'spam', 'other'])[min(CASE reason WHEN 'illegal' THEN 1 WHEN 'spam' THEN 3 WHEN 'other' THEN 4 ELSE 2 END)] AS lane
  FROM reports WHERE case_id = sqlc.arg(id)
) s
WHERE c.id = sqlc.arg(id)
RETURNING c.*;

-- name: Followers :one
SELECT count(*)::int FROM shares WHERE deck_id = $1;

-- name: OpenCases :many
SELECT c.*, (SELECT count(*) FROM reports r WHERE r.case_id = c.id)::int AS reports,
  coalesce((SELECT array_agg(DISTINCT r.reason) FROM reports r WHERE r.case_id = c.id), '{}')::text[] AS reasons
FROM cases c
WHERE c.opened_at IS NOT NULL AND c.resolved_at IS NULL
  AND (sqlc.narg(lane)::text IS NULL OR c.lane = sqlc.narg(lane))
  AND (sqlc.narg(target)::smallint IS NULL OR c.target_kind = sqlc.narg(target))
ORDER BY array_position(ARRAY['illegal', 'safety', 'spam', 'other', 'waves', 'flags'], c.lane), c.score DESC, c.opened_at
LIMIT 100;

-- name: CaseByID :one
SELECT * FROM cases WHERE id = $1;

-- name: CaseReports :many
SELECT * FROM reports WHERE case_id = $1 ORDER BY id;

-- name: DeriveCase :one
-- Resolved by the first staff action with the case that isn't undone: dismissed, or acted on.
UPDATE cases c SET (resolved_at, resolved_by, outcome) = (
  SELECT a.created_at, a.actor_id, (CASE WHEN a.kind = 'case.dismiss' THEN 2 ELSE 1 END)::smallint
  FROM staff_actions a WHERE a.case_id = c.id AND a.undone_at IS NULL ORDER BY a.id LIMIT 1
)
WHERE c.id = $1
RETURNING c.*;

-- name: CaseReporters :many
SELECT DISTINCT reporter_id FROM reports WHERE case_id = $1;

-- name: Windows :many
-- Reports, publications and new accounts in each of the last 29 days (24-hour windows, the latest
-- last).
SELECT
  (SELECT count(*) FROM reports r WHERE r.created_at >= w AND r.created_at < w + interval '1 day')::int AS reports,
  (SELECT count(*) FROM publications p WHERE p.published_at >= w AND p.published_at < w + interval '1 day')::int AS publications,
  (SELECT count(*) FROM users u WHERE u.created_at >= w AND u.created_at < w + interval '1 day')::int AS signups
FROM generate_series(now() - interval '29 days', now() - interval '1 day', interval '1 day') w
ORDER BY w;

-- name: WaveLately :one
SELECT EXISTS (SELECT 1 FROM cases WHERE kind = 2 AND data->>'metric' = $1::text AND created_at > now() - interval '24 hours');

-- name: AddCase :one
INSERT INTO cases (id, kind, target_kind, target_id, lane, opened_at, data) VALUES ($1, $2, $3, $4, $5, now(), $6)
RETURNING *;

-- name: ReportedLately :many
SELECT c.target_kind, c.target_id, count(*)::int AS reports FROM reports r JOIN cases c ON c.id = r.case_id
WHERE r.created_at > now() - interval '24 hours' AND c.kind = 1
GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 25;

-- name: ReportersLately :many
SELECT r.reporter_id, count(*)::int AS reports FROM reports r
WHERE r.created_at > now() - interval '24 hours' GROUP BY 1 ORDER BY 2 DESC LIMIT 25;

-- name: PublishedLately :many
SELECT p.deck_id FROM publications p WHERE p.published_at > now() - interval '24 hours' ORDER BY p.published_at DESC LIMIT 25;

-- name: OpenFlag :one
SELECT EXISTS (SELECT 1 FROM cases WHERE kind = 3 AND target_kind = $1 AND target_id = $2 AND resolved_at IS NULL);

-- name: PublishedBy :one
-- Decks someone published in the last 24 hours.
SELECT count(*)::int FROM publications p JOIN decks d ON d.id = p.deck_id
WHERE d.owner_id = $1 AND p.published_at > now() - interval '24 hours';

-- name: PublishedFrom :one
-- Decks published in the last 24 hours by people seen at an address.
SELECT count(*)::int FROM publications p JOIN decks d ON d.id = p.deck_id
WHERE p.published_at > now() - interval '24 hours' AND d.owner_id IN (SELECT user_id FROM user_ips WHERE ip = $1);

-- name: DeckBrief :one
SELECT d.id, d.name, d.owner_id, p.slug FROM decks d LEFT JOIN publications p ON p.deck_id = d.id WHERE d.id = $1;
