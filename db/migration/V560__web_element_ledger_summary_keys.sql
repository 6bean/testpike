-- Migration: WE batch S5 — factory seeds for the page-element
-- library prompt-summary bridge (ai.gen.ledger-summary.* key trio).
--
-- Why these rows exist:
--   Stage-2 script generation now injects a capped summary of the project's
--   CONFIRMED page elements so the model references verified locators instead
--   of inventing new ones. AI features ship ON (factory default = enabled),
--   and both budgets are hot-updatable from the config page; the reader
--   (WebElementLedgerSummaryBridge) fails closed to these factory values and
--   mirrors the same ranges, while the core prompt-summary door clamps the
--   row limit to 1..500 server-side.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.gen.ledger-summary.enabled', 'true', '系统', 'bool', 0,
     '脚本生成注入页面元素库已确认元素摘要的开关（默认开启；开启后生成动作脚本时把本项目已确认元素（名称+定位锚）摘要注入提示，模型优先引用已建档元素；关闭后提示恢复旧形态、零外呼；账本无已确认元素或读取失败时自动不注入、生成流程不受影响；修改即时生效）'),
    ('ai.gen.ledger-summary.max-rows', '60', '系统', 'string', 0,
     '摘要注入的元素行数上限（默认 60，允许 1~500；超出上限的条目不注入；服务端同区间钳制取交集；修改即时生效）'),
    ('ai.gen.ledger-summary.max-chars', '1800', '系统', 'string', 0,
     '摘要注入正文的字符预算（默认 1800，允许 200~8000；超预算时整行丢弃并追加一行截断说明，不出现半行；修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
