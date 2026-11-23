-- Migration V578: R62 auto-follow for the AI drafting-job panel (
-- D-face, first consumer of useListAutoFollow). Adds the fourth hot-updated
-- knob of the shared tail-window config reader (frontend/src/composables/
-- tailWindowConfig.ts): the beat the job drawer re-reads its list while any
-- card is still queued/generating. Absent or out-of-range rows fall back to
-- the factory default on the read side. Applied manually to the dev DB
-- (Flyway not enabled; db/migration is reference + replay source for
-- schema-init, regenerated via tools/ops/gen_schema_init.sh).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ui.list.auto-follow-ms', '2000', '系统', 'int', 0, '任务面板自动重读列表的时间间隔（默认 2000 毫秒；可设 1000~60000，保存即生效；面板里还有进行中的任务时按此间隔自动更新状态，全部完成后自动停止，无需手动刷新）')
ON CONFLICT (config_key) DO NOTHING;

-- R61: the mock-docs help chapter gains the section describing the new
-- behaviour in the same batch (filing a drafting job now keeps its own
-- status visible without user action). Idempotent: the append is guarded by
-- NOT LIKE on its marker heading, a replay after the amendment writes zero
-- rows.
UPDATE tp_help_doc
   SET body_md = body_md || E'\n\n## 起草任务状态自动更新\n'
       || E'点「提交任务」后任务立即排队执行，任务抽屉会自动保持最新状态：卡片从「排队中」「生成中」走到「待勾选确认」或「失败」的全过程无需你做任何刷新操作。\n'
       || E'- 抽屉顶部出现「有任务正在生成，状态会自动更新」提示时，说明列表正在按固定间隔自动重读（间隔可在 系统设置 → 系统配置 中调整）；所有任务都已出结果后自动重读会停止，提示随之消失。\n'
       || E'- 草稿生成完成后卡片变为「待勾选确认」，点「查看草稿」进入勾选确认流程——只有勾选的条目会进入规则库，来源自动标注为 AI 起草。\n'
       || E'- 生成失败的卡片显示「失败」标签，点卡片上的错误说明可查看原因；调整需求描述后可以再次提交新任务。\n'
       || E'- 中途关闭任务抽屉不影响生成过程，重新打开「起草任务」时列表按最新状态展示。\n'
 WHERE domain_code = 'T'
   AND title LIKE '仿真文档%'
   AND body_md NOT LIKE '%起草任务状态自动更新%';
