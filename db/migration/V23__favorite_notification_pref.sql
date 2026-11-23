
CREATE TABLE IF NOT EXISTS tp_favorite (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES tp_user (id),
    entity_type VARCHAR(20) NOT NULL,
    entity_id   BIGINT NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uk_favorite UNIQUE (user_id, entity_type, entity_id)
);
COMMENT ON TABLE tp_favorite IS 'P4 用户收藏（幂等键=uk_favorite，单用户上限 500）';
COMMENT ON COLUMN tp_favorite.entity_type IS '实体类型：case/requirement/defect/project';
COMMENT ON COLUMN tp_favorite.entity_id IS '实体主键（不建 FK 防跨域耦合；可见性查询期 H1 过滤）';
CREATE INDEX IF NOT EXISTS idx_favorite_user_type ON tp_favorite (user_id, entity_type);

CREATE TABLE IF NOT EXISTS tp_notification_pref (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES tp_user (id),
    event_type VARCHAR(40) NOT NULL,
    enabled    SMALLINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uk_notification_pref UNIQUE (user_id, event_type)
);
COMMENT ON TABLE tp_notification_pref IS 'P5 通知订阅偏好（无记录=默认订阅，白名单事件禁关在服务层拒绝）';
CREATE INDEX IF NOT EXISTS idx_notification_pref_user ON tp_notification_pref (user_id);
