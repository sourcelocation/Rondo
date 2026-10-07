-- name: EnsureUser :execrows
-- A new account gets the username Rondo picked for it, recorded in its names.
WITH added AS (
  INSERT INTO users (id, username) VALUES (sqlc.arg(id), sqlc.arg(username)) ON CONFLICT DO NOTHING
  RETURNING id, username, created_at
)
INSERT INTO names (id, user_id, kind, value, created_at) SELECT sqlc.arg(name_id), id, 1, username, created_at FROM added;

-- name: User :one
SELECT * FROM users WHERE id = $1;

-- name: SetName :one
UPDATE users SET name = $2 WHERE id = $1 RETURNING *;

-- name: SetProUntil :exec
UPDATE users SET pro_until = $2 WHERE id = $1;

-- name: SetStripeCustomer :exec
UPDATE users SET stripe_customer = $2 WHERE id = $1;

-- name: DeleteUser :exec
DELETE FROM users WHERE id = $1;

-- name: Deck :one
SELECT id, owner_id, name, description, language, icon, color, deleted_at FROM decks WHERE id = $1;

-- name: Shares :many
SELECT s.user_id, s.role, u.name, u.username FROM shares s JOIN users u ON u.id = s.user_id WHERE s.deck_id = $1 ORDER BY s.created_at;

-- name: PutShare :exec
INSERT INTO shares (deck_id, user_id, role) VALUES ($1, $2, $3) ON CONFLICT (deck_id, user_id) DO UPDATE SET role = EXCLUDED.role;

-- name: AddShare :exec
INSERT INTO shares (deck_id, user_id, role) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING;

-- name: RemoveShare :exec
DELETE FROM shares WHERE deck_id = $1 AND user_id = $2;

-- name: CreateInvite :exec
INSERT INTO invites (id, token_hash, deck_id, role, email, created_by, expires_at) VALUES ($1, $2, $3, $4, $5, $6, $7);

-- name: Invites :many
SELECT * FROM invites WHERE deck_id = $1 AND used_at IS NULL AND expires_at > now() ORDER BY expires_at;

-- name: InviteByHash :one
SELECT * FROM invites WHERE token_hash = $1 AND used_at IS NULL AND expires_at > now();

-- name: Invite :one
SELECT * FROM invites WHERE id = $1;

-- name: DeleteInvite :exec
DELETE FROM invites WHERE id = $1;

-- name: UseInvite :exec
UPDATE invites SET used_at = now() WHERE id = $1;

-- name: Publication :one
SELECT p.*, (SELECT count(*) FROM shares s WHERE s.deck_id = p.deck_id) AS followers FROM publications p WHERE p.deck_id = $1;

-- name: Publish :exec
INSERT INTO publications (deck_id, slug, listed) VALUES ($1, $2, true) ON CONFLICT (deck_id) DO UPDATE SET listed = true;

-- name: Unpublish :exec
UPDATE publications SET listed = false WHERE deck_id = $1;

-- name: PublicationBySlug :one
SELECT * FROM publications WHERE slug = $1 AND listed AND removed_at IS NULL;

-- name: Subtree :many
WITH RECURSIVE sub AS (SELECT x.id FROM decks x WHERE x.id = $1 UNION SELECT c.id FROM decks c JOIN sub ON c.parent_id = sub.id WHERE c.deleted_at IS NULL)
SELECT (to_jsonb(d) - 'seq')::text FROM decks d WHERE d.id IN (SELECT id FROM sub);

-- name: Samples :many
WITH RECURSIVE sub AS (SELECT x.id FROM decks x WHERE x.id = $1 UNION SELECT c.id FROM decks c JOIN sub ON c.parent_id = sub.id WHERE c.deleted_at IS NULL)
SELECT (to_jsonb(n) - 'seq' - 'search_text')::text FROM notes n WHERE n.deck_id IN (SELECT id FROM sub) AND n.deleted_at IS NULL ORDER BY n.id LIMIT 5;

-- name: TemplatesByID :many
SELECT (to_jsonb(t) - 'seq')::text FROM templates t WHERE t.id = ANY($1::uuid[]);

-- name: PutSubscription :exec
INSERT INTO subscriptions (provider, ref, user_id, product, status, period_end, auto_renew, sandbox, updated_at)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, now())
ON CONFLICT (provider, ref) DO UPDATE SET user_id = EXCLUDED.user_id, product = EXCLUDED.product, status = EXCLUDED.status,
  period_end = EXCLUDED.period_end, auto_renew = EXCLUDED.auto_renew, sandbox = EXCLUDED.sandbox, updated_at = now();

-- name: Subscriptions :many
SELECT * FROM subscriptions WHERE user_id = $1 ORDER BY updated_at DESC;

-- name: Media :one
SELECT * FROM media WHERE hash = $1;

-- name: AddMedia :exec
INSERT INTO media (hash, size, mime, uploaded_by) VALUES ($1, $2, $3, $4) ON CONFLICT DO NOTHING;

-- name: VerifyMedia :exec
UPDATE media SET verified = true WHERE hash = $1;

-- name: MediaUsage :one
SELECT coalesce(sum(m.size), 0)::bigint FROM media m
WHERE m.hash IN (SELECT nm.hash FROM note_media nm JOIN notes n ON n.id = nm.note_id WHERE n.owner_id = $1);

-- name: UnusedMedia :many
SELECT hash FROM media m WHERE created_at < $1 AND NOT EXISTS (SELECT 1 FROM note_media nm WHERE nm.hash = m.hash) LIMIT 1000;

-- name: DeleteMedia :exec
DELETE FROM media WHERE hash = $1;

-- name: OwnMedia :many
SELECT DISTINCT nm.hash FROM note_media nm JOIN notes n ON n.id = nm.note_id WHERE n.owner_id = $1;

-- name: Lending :many
-- An owner's decks that others borrow (followers included) or have a pending invitation to.
SELECT s.deck_id FROM shares s JOIN decks d ON d.id = s.deck_id WHERE d.owner_id = $1
UNION
SELECT i.deck_id FROM invites i JOIN decks d ON d.id = i.deck_id
WHERE d.owner_id = $1 AND i.used_at IS NULL AND i.expires_at > now();

-- name: RefreshPublications :exec
-- Discover's copy of each listing (or one, [deck]): its deck's name, language and author, and counts.
WITH RECURSIVE tree AS (
  SELECT p.deck_id AS root, p.deck_id AS id FROM publications p
  WHERE p.listed AND (sqlc.narg(deck)::uuid IS NULL OR p.deck_id = sqlc.narg(deck))
  UNION
  SELECT tree.root, c.id FROM decks c JOIN tree ON c.parent_id = tree.id WHERE c.deleted_at IS NULL
), counts AS (
  SELECT tree.root, count(n.id)::int AS notes FROM tree LEFT JOIN notes n ON n.deck_id = tree.id AND n.deleted_at IS NULL
  GROUP BY tree.root
)
UPDATE publications p SET
  name = d.name, language = d.language, owner_id = d.owner_id, gone = d.deleted_at IS NOT NULL OR d.owner_id IS NULL,
  followers = (SELECT count(*) FROM shares s WHERE s.deck_id = p.deck_id),
  hot = (SELECT count(*) FROM shares s WHERE s.deck_id = p.deck_id AND s.created_at > now() - interval '7 days'),
  notes = counts.notes,
  refreshed_at = now()
FROM decks d, counts
WHERE d.id = p.deck_id AND counts.root = p.deck_id;
