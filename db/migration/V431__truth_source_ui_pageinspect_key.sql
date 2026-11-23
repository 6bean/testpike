-- Assertion truth-source, level 3 : with the switch on
-- (factory default), the stage2 script-generation pass probes the target
-- page's interactive-element candidates by default for every project, so
-- step locators cite controls that were verified to exist instead of guessed
-- ones. Turning this row off restores the exact legacy shape — the probe only
-- runs for projects whose own tp_project.ai_page_inspect_enabled column is on
-- (opt-in), and column-on projects keep their probe either way (the old
-- behaviour never shrinks). Projects without a usable non-production
-- environment, probe failures and read-path errors inject nothing and never
-- interrupt generation. This grounding is deliberately decoupled from the
-- per-project column: the column alone keeps deciding the opt-in cohort when
-- this row is off.
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.gen.truth-source.ui-pageinspect', 'true', '系统', 'bool', 0,
     'UI 控件真值注入开关（默认开启；开启后脚本生成为所有项目默认探查目标页可交互控件清单并注入提示，控件定位以实测候选为据、禁凭空猜；关闭后仅项目设置中开启页面探查的项目才探查；目标页无可用非生产环境或探查失败时不注入、生成流程不受影响；修改即时生效）')
ON CONFLICT (config_key) DO NOTHING;
