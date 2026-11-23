
ALTER TABLE tp_api_spec_preview ALTER COLUMN spec_id DROP NOT NULL;

ALTER TABLE tp_api_spec_preview ADD COLUMN IF NOT EXISTS source_document_id bigint REFERENCES tp_document (id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_api_spec_preview_document
    ON tp_api_spec_preview (source_document_id, project_id)
    WHERE spec_id IS NULL;
