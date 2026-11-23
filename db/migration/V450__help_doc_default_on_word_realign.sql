-- Help chapters for AI application points still advertised a factory-closed
-- state after the default-on batch flipped their switches. Display text only:
-- align the wording with the shipped defaults and keep the honest guidance
-- sentence for the off state (where to re-open the switch). Idempotent replay
-- via IS DISTINCT FROM (guard on the NEW text).

UPDATE tp_help_doc SET body_md = replace(body_md,
  '4. **开关**：采纳动作默认关闭。需要使用时请到「系统设置 → APP 自动化」把自愈采纳开关打开，改完即时生效不用重启；开关只拦「采纳」，看单与驳回随时可用。',
  '4. **开关**：采纳动作默认开启；被关闭时采纳会被拒绝并提示「自愈采纳开关未在系统设置中开启」。开关位于「系统设置 → APP 自动化」，改完即时生效不用重启；开关只拦「采纳」，看单与驳回随时可用。')
 WHERE id = 60
   AND body_md LIKE '%4. **开关**：采纳动作默认关闭。需要使用时请到「系统设置 → APP 自动化」把自愈采纳开关打开，改完即时生效不用重启；开关只拦「采纳」，看单与驳回随时可用。%';

UPDATE tp_help_doc SET body_md = replace(body_md,
  '- 这个功能默认关闭：开关未开启时会弹出提示卡并给出「去系统设置」直达入口，由管理员在「系统设置 → APP 自动化」开启后重试。',
  '- 这个功能默认开启：若被管理员关闭，会弹出提示卡并给出「去系统设置」直达入口，指路写明开关名称与所在页面，开启后即可重试。')
 WHERE id = 62
   AND body_md LIKE '%- 这个功能默认关闭：开关未开启时会弹出提示卡并给出「去系统设置」直达入口，由管理员在「系统设置 → APP 自动化」开启后重试。%';

UPDATE tp_help_doc SET body_md = replace(body_md,
  '- 这个功能默认关闭：未开启时弹窗内会出现提示卡与「去系统设置」直达入口，由管理员在「系统设置 → APP 自动化」开启后重试。',
  '- 这个功能默认开启：若被管理员关闭，弹窗内会出现提示卡与「去系统设置」直达入口，指路写明开关名称与所在页面，开启后即可重试。')
 WHERE id = 63
   AND body_md LIKE '%- 这个功能默认关闭：未开启时弹窗内会出现提示卡与「去系统设置」直达入口，由管理员在「系统设置 → APP 自动化」开启后重试。%';

UPDATE tp_help_doc SET body_md = replace(body_md,
  '功能开关（默认关闭）与权限键双闸才放行发起，防止误触消耗。',
  '功能开关（默认开启，可逐点关闭）与权限键双闸才放行发起，防止误触消耗。')
 WHERE id = 72
   AND body_md LIKE '%功能开关（默认关闭）与权限键双闸才放行发起，防止误触消耗。%';

UPDATE tp_help_doc SET body_md = replace(body_md,
  '- 点「自学」提示功能未开启？——这是 AI 消耗功能，默认关闭；请管理员在「系统设置 → APP 自动化」开启后再来。',
  '- 点「自学」提示功能未开启？——这是 AI 消耗功能，出厂默认已开启；若被管理员关闭，请按提示中的路径（系统设置 → APP 自动化）重新开启后再来。')
 WHERE id = 72
   AND body_md LIKE '%- 点「自学」提示功能未开启？——这是 AI 消耗功能，默认关闭；请管理员在「系统设置 → APP 自动化」开启后再来。%';

UPDATE tp_help_doc SET body_md = replace(body_md,
  'SQL 类任务注入测试库表结构字典，防止模型凭空编造字段（默认关闭，需管理员开启）',
  'SQL 类任务注入测试库表结构字典，防止模型凭空编造字段（默认开启，可在系统设置中关闭）')
 WHERE id = 24
   AND body_md LIKE '%SQL 类任务注入测试库表结构字典，防止模型凭空编造字段（默认关闭，需管理员开启）%';

