-- Migration: hot cap key for the named project resource-pool entries that
-- ride one stage-2 script prompt .
--
-- Why this file exists:
--   The cap value itself (30) is unchanged — only its form moves from a
--   static constant to a hot key, per the "thresholds configurable, hot
--   updatable, no hardcoding" standard. The fail-closed reader
--   (AiGenServiceImpl.resourceSegmentCap) falls back to 30 for any missing,
--   non-numeric or out-of-window value, and the core write side
--   (ConfigServiceImpl) refuses saves outside 10~100, so neither a silent
--   prompt shrink nor a silent prompt growth can happen through this dial.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.gen.resource-pool.segment-cap', '30', '系统', 'string', 0, '脚本生成提示中列出的项目资源（数据工厂/接口集）条目数上限（默认 30，允许 10~100；超出范围的保存会被拒绝，读取到非法值时按默认 30 生效。清单按与当前用例的相关度排序，环境类地址不受本上限限制。保存即生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
