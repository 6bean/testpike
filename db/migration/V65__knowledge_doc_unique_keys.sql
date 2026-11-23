--   ① uq_kb_doc_entity(source_type,project_id,source_ref) WHERE source_ref IS NOT NULL
--   ② uq_kb_doc_title(pack_id,project_id,title) WHERE source_ref IS NULL

ALTER TABLE public.tp_knowledge_doc
    DROP CONSTRAINT IF EXISTS uq_knowledge_doc_pack_title;

CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_doc_entity
    ON public.tp_knowledge_doc (source_type, project_id, source_ref)
    WHERE source_ref IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_doc_title
    ON public.tp_knowledge_doc (pack_id, project_id, title)
    WHERE source_ref IS NULL;
