-- Domain-experience injection cap (behavior kernel item 9): the maximum
-- number of rendered lines the dialog L0 head injects as the "project
-- experience" section, sourced from distilled project memory
-- (tp_ai_ctx_memory) and the SKILL usage ledger (tp_skill_usage). A project
-- without experience rows gets no section at all (zero noise); when there
-- are more rows than the cap the newest are kept and the cut is disclosed in
-- the tail. Read hot per turn — a value change lands on the next dialog turn,
-- no restart. Ships open with the annotation budget of 20 lines: the section
-- stays well under the grounding material it must never outweigh.
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.behavior.experience-max-lines', '20', '系统', 'int', 0, '对话上下文「项目经验段」的注入行数上限：按最近更新时间保留前 N 行，其余本轮不注入并会如实标注；项目没有经验时该段整体不出现。默认 20；正整数；修改后下一轮对话生效')
ON CONFLICT (config_key) DO NOTHING;
