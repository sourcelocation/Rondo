-- +goose Up
-- Reports and cases. A case gathers what points at one deck or person (reports), or at a day that
-- stands out (a wave), or at something a rule flags. Reports weigh what their reporter's record is
-- worth; a case reaches staff once its weight crosses a threshold. Resolving is a staff action
-- (acting with the case, or case.dismiss), so the case's outcome is derived and can be undone.

CREATE TABLE cases (
  id          uuid PRIMARY KEY,                          -- UUIDv7
  kind        smallint NOT NULL,                         -- 1 reports, 2 a wave, 3 a rule's flag
  target_kind smallint,                                  -- 1 a deck, 2 a person
  target_id   uuid,
  lane        text NOT NULL,                             -- illegal, safety, spam, other, waves, flags
  score       real NOT NULL DEFAULT 0,
  opened_at   timestamptz,                               -- crossed the threshold; NULL: not shown to staff
  resolved_at timestamptz,
  resolved_by uuid REFERENCES users ON DELETE SET NULL,
  outcome     smallint,                                  -- 1 acted on, 2 dismissed
  data        jsonb NOT NULL DEFAULT '{}',               -- a wave's or a flag's details
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX cases_target ON cases (target_kind, target_id) WHERE kind = 1 AND resolved_at IS NULL;
CREATE INDEX cases_queue ON cases (opened_at) WHERE opened_at IS NOT NULL AND resolved_at IS NULL;

CREATE TABLE reports (
  id          uuid PRIMARY KEY,                          -- UUIDv7
  reporter_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
  case_id     uuid NOT NULL REFERENCES cases ON DELETE CASCADE,
  reason      text NOT NULL,
  law         text,                                      -- for illegal content: which law
  note        text,
  weight      real NOT NULL,                             -- what it counted when it was made
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (reporter_id, case_id)
);
CREATE INDEX reports_reporter ON reports (reporter_id, created_at);
CREATE INDEX reports_case ON reports (case_id);

-- +goose Down
DROP TABLE reports, cases;
