-- ---------------------------------------------------------------------
--   docker exec testpike-postgres psql -U testpike -d testpike -f /dev/stdin < db/migration/V4__case_requirement.sql
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS tp_case_requirement (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id        BIGINT NOT NULL REFERENCES tp_test_case(id),
    requirement_id BIGINT NOT NULL REFERENCES tp_requirement(id),
    created_by     BIGINT,
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uk_case_requirement UNIQUE (case_id, requirement_id)
);

CREATE INDEX IF NOT EXISTS idx_case_req_requirement ON tp_case_requirement (requirement_id);
COMMENT ON TABLE  tp_case_requirement IS '用例-需求点多对多（覆盖矩阵依据）';
COMMENT ON COLUMN tp_case_requirement.case_id IS '用例 ID（H1：同项目校验在应用层）';
COMMENT ON COLUMN tp_case_requirement.requirement_id IS '需求点 ID';
