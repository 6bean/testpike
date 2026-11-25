-- Dual provenance for mock rules: source/source_ref stay the birth facts
-- (openapi + spec:{id} for contract rows, nl-ai + job:{id} for AI-born rows).
-- enriched_by records which AI enrichment job last improved a contract-direct
-- rule, so the rule list can badge "contract direct" vs "AI enriched" and the
-- enrichment trail stays retracable to the job row.
ALTER TABLE tp_mock_stub ADD COLUMN IF NOT EXISTS enriched_by VARCHAR(120);
COMMENT ON COLUMN tp_mock_stub.enriched_by IS 'Provenance anchor of the AI enrichment job that improved this contract-direct rule (job:{id}); NULL means the rule was filed as-is';
