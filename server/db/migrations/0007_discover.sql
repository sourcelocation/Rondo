-- +goose Up
-- Discover reads one table: each listing keeps a copy of its deck's name, language and author, and
-- counts (followers, notes, follows in the last week for Hot), refreshed every ten minutes and when
-- something about it changes. Each sort reads its own index.

ALTER TABLE publications
  ADD COLUMN name         text,
  ADD COLUMN language     text,
  ADD COLUMN owner_id     uuid,
  ADD COLUMN gone         boolean NOT NULL DEFAULT false,   -- the deck was deleted, or has no author
  ADD COLUMN followers    integer NOT NULL DEFAULT 0,
  ADD COLUMN notes        integer NOT NULL DEFAULT 0,
  ADD COLUMN hot          integer NOT NULL DEFAULT 0,       -- follows in the last 7 days
  ADD COLUMN refreshed_at timestamptz;

CREATE INDEX publications_top ON publications (followers DESC, published_at DESC) WHERE listed AND removed_at IS NULL AND NOT gone;
CREATE INDEX publications_new ON publications (published_at DESC) WHERE listed AND removed_at IS NULL AND NOT gone;
CREATE INDEX publications_hot ON publications (hot DESC, followers DESC) WHERE listed AND removed_at IS NULL AND NOT gone;
CREATE INDEX publications_name ON publications USING gin (name gin_trgm_ops);
CREATE INDEX shares_created ON shares (created_at);

UPDATE publications p SET
  name = d.name, language = d.language, owner_id = d.owner_id, gone = d.deleted_at IS NOT NULL OR d.owner_id IS NULL,
  followers = (SELECT count(*) FROM shares s WHERE s.deck_id = p.deck_id),
  hot = (SELECT count(*) FROM shares s WHERE s.deck_id = p.deck_id AND s.created_at > now() - interval '7 days'),
  refreshed_at = now()
FROM decks d WHERE d.id = p.deck_id;

-- +goose Down
DROP INDEX shares_created;
ALTER TABLE publications DROP COLUMN name, DROP COLUMN language, DROP COLUMN owner_id, DROP COLUMN gone,
  DROP COLUMN followers, DROP COLUMN notes, DROP COLUMN hot, DROP COLUMN refreshed_at;
