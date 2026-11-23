-- Migration: apitest request explicit member order .
-- Why: the tree order used to be implicit (id asc); the orchestration batch lets
--      users maintain the order inside a collection, and the launch preview plus
--      whole-set execution freeze their definitions in that order.
-- Semantics: sort_order is 1..n within a collection (smaller = front); ties fall
--      back to id for a stable ordering.

ALTER TABLE tp_apitest_request
    ADD COLUMN IF NOT EXISTS sort_order INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN tp_apitest_request.sort_order IS
    'User-maintained position inside the collection (1..n, ascending = front). Backfilled to the legacy id-order baseline; new rows append at max+1.';

-- Backfill so the existing "tree order = id order" contract survives the column:
-- rank each member by id within its collection.
UPDATE tp_apitest_request r
   SET sort_order = x.rn
  FROM (SELECT id, ROW_NUMBER() OVER (PARTITION BY collection_id ORDER BY id) AS rn
          FROM tp_apitest_request) x
 WHERE r.id = x.id;

CREATE INDEX IF NOT EXISTS idx_apitest_request_collection_sort
    ON tp_apitest_request (collection_id, sort_order);
