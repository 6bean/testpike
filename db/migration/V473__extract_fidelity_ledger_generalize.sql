-- EE batch (piece 1, A2 single-source): generalize the extraction
-- fidelity ledger so the spec-extract and qa-gen planes can book the same
-- account instead of rejecting over-sized inputs outright.
-- * document_id -> ref_id and extract_token -> run_id: the identity pair now
--   scopes a fidelity face for any extraction task, not only a document
--   generation. tp_document.extract_token and tp_ai_call_log.extract_token
--   are columns of OTHER tables that merely share the word - they stay.
-- * task_type tags which plane booked the row; the factory default keeps
--   every existing document row attributed without a data backfill.
-- * the FK to tp_document stays: every referenced row today is a document,
--   so the cascade account is unchanged.
ALTER TABLE tp_doc_extract_fidelity RENAME COLUMN document_id TO ref_id;
ALTER TABLE tp_doc_extract_fidelity RENAME COLUMN extract_token TO run_id;
ALTER TABLE tp_doc_extract_fidelity ADD COLUMN task_type VARCHAR(32) NOT NULL DEFAULT 'doc-extract';

COMMENT ON COLUMN tp_doc_extract_fidelity.ref_id IS 'Row the fidelity face belongs to (a document today; spec/qa targets join in later pieces) - cascades with tp_document while rows reference documents';
COMMENT ON COLUMN tp_doc_extract_fidelity.run_id IS 'Generation token minted by core; (ref_id, run_id) scopes the fidelity face to one extraction run';
COMMENT ON COLUMN tp_doc_extract_fidelity.task_type IS 'Plane that booked the run: doc-extract (factory default, document requirement extraction), spec-extract, qa-gen';
