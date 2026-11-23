
CREATE TABLE IF NOT EXISTS tp_llm_config (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    provider    VARCHAR(50)  NOT NULL,
    model_name  VARCHAR(100) NOT NULL,
    api_key     TEXT,
    base_url    VARCHAR(500),
    status      SMALLINT NOT NULL DEFAULT 1,
    is_default  SMALLINT NOT NULL DEFAULT 0,
    description VARCHAR(500),
    created_by  BIGINT,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_llm_config_name ON tp_llm_config (name);

ALTER TABLE tp_project ADD COLUMN IF NOT EXISTS llm_config_id BIGINT;

ALTER TABLE tp_ai_task ADD COLUMN IF NOT EXISTS model_name VARCHAR(100);
