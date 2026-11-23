-- Help-chapter wording for the AI chat composer display rule (user 10-28 order).
-- display preference off by default (Enter sends directly; Shift+Enter breaks a
-- line). The V297 usage sentence still promises an always-visible button, which
-- would contradict the new honest default; the FAQ gains the missing-button path.
-- Discipline: screen-facing body only (operational wording, zero internal codes);
-- idempotent via the LIKE guard; dev DB applies manually, schema-init regenerates.
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike -v ON_ERROR_STOP=1 -f - < this file

UPDATE tp_help_doc
SET body_md = replace(
        body_md,
        '2. 输入问题后回车或点「发送」：回答逐字流出',
        '2. 输入问题后按 Enter 直接发送（Shift+Enter 换行）；如习惯按钮发送，可在「AI 对话」标题栏右侧的设置入口打开「显示发送按钮」：回答逐字流出'),
    version = '2.96', updated_at = now()
WHERE domain_code = 'AI' AND body_md LIKE '%输入问题后回车或点「发送」%';

UPDATE tp_help_doc
SET body_md = replace(
        body_md,
        '- 发送钮点不动？——顶栏还没选项目，选一个即可，提示会点名缺什么。',
        '- 找不到「发送」按钮？——默认不发按钮、按 Enter 直接发送（Shift+Enter 换行）；想显示按钮，在「AI 对话」标题栏右侧的设置入口打开「显示发送按钮」，选择会记住。发送钮点不动？——顶栏还没选项目，选一个即可，提示会点名缺什么。'),
    version = '2.96', updated_at = now()
WHERE domain_code = 'AI' AND body_md LIKE '%发送钮点不动%' AND body_md NOT LIKE '%找不到「发送」按钮%';
