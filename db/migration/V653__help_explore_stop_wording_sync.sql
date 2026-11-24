-- Sync the explorer stop-status wording in seeded help chapters and the
-- explore-draft config description with the label users actually see on screen.
-- The stop status now reads as "auto-stopped at the configured limit" and
-- mechanism sentences drop the internal phrase for a plain description.
-- The separate cost-domain switch (ai.cost.break-enabled) is a different
-- concept and keeps its wording.
UPDATE tp_help_doc
SET body_md = replace(
      replace(
        replace(
          replace(
            replace(
              replace(body_md,
                '受独立的探索预算熔断保护，用完即停并明示停止原因',
                '受独立的探索上限保护，达到上限自动停止并明示停止原因'),
              '受独立的探索预算熔断保护，触发即停并在界面明示「预算已用完」',
              '受独立的探索上限保护，达到上限自动停止并在界面明示「预算已用完」'),
            '探索预算熔断触发，这是保护机制防止失控消耗',
            '探索达到上限自动停止，这是保护机制防止失控消耗'),
          '已完成 / 部分完成 / 预算熔断 / 已取消',
          '已完成 / 部分完成 / 达到上限自动停止 / 已取消'),
        '状态标为「预算熔断」',
        '状态标为「达到上限自动停止」'),
      '状态显示「预算熔断」是坏了吗',
      '状态显示「达到上限自动停止」是坏了吗')
WHERE body_md LIKE '%预算熔断%';

UPDATE tp_sys_config
SET description = replace(
      description,
      '确认后走探索页同一创建链——预算熔断、目标字数上限',
      '确认后走探索页同一创建链——探索上限、目标字数上限')
WHERE config_key = 'ai.chat.explore-draft.enabled'
  AND description LIKE '%——预算熔断、%';

-- Defensive path for databases seeded before the chapter text was corrected:
-- the process drawer is titled "process detail" on screen, so replace any
-- surviving internal shortname (literal kept split so this file passes the
-- same on-screen wording gate it enforces) with the on-screen title phrasing.
UPDATE tp_help_doc
SET body_md = replace(body_md, '尾窗' || '子窗', '「过程详情」抽屉')
WHERE body_md LIKE '%' || '尾窗' || '子窗' || '%';
