-- document lineage chain: rows sharing one logical document
-- (project + display name + file type) belong to one lineage group so that the
-- sha256 duplicate gate becomes group-scoped — re-uploading identical content
-- inside the same lineage is refused, identical content under a different name
-- is admitted as a separate document (anti-duplicate and anti-iteration no
-- longer fight). lineage_group_id = root document id of the group; the service
-- layer guarantees the value for new rows (first member self-references after
-- insert), therefore the column stays nullable at DB level by design.
ALTER TABLE tp_document ADD COLUMN lineage_group_id BIGINT;

UPDATE tp_document d
   SET lineage_group_id = g.root_id
  FROM (SELECT MIN(id) AS root_id, project_id, name, file_type
          FROM tp_document
         GROUP BY project_id, name, file_type) g
 WHERE d.project_id = g.project_id
   AND d.name = g.name
   AND d.file_type = g.file_type;

CREATE INDEX IF NOT EXISTS idx_tp_document_lineage_group
    ON tp_document (lineage_group_id);

ALTER TABLE tp_document DROP CONSTRAINT tp_document_file_hash_key;

ALTER TABLE tp_document
    ADD CONSTRAINT tp_document_lineage_hash_unique
    UNIQUE (lineage_group_id, file_hash);
