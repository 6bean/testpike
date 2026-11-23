-- Migration V568: help-chapter amendment for the ledger retention twin loop
-- (WE batch, j-surface, R61). The two seeded config keys promised
-- automatic clean-up from day one (V544); the sweep is now wired, so the
-- chapter states the actual behaviour: aged candidates auto-retire, retired
-- rows age out, and where the two windows are adjustable. Idempotent: the
-- append is guarded by NOT LIKE on the block title, replay writes zero rows.

UPDATE tp_help_doc
   SET body_md = body_md || E'\n\n## 自动留存清理（新增能力）\n'
       || E'元素库带两条留存时间窗，防止长期堆积：\n'
       || E'- 候选超时自动退役：登记后一直未被执行验证确认的候选元素，超过「候选待验证时长上限」天（默认 90 天）会在每日清理任务中自动转为退役，并在该元素的操作记录里留下一条与人工退役同样的记录；想继续使用的候选请在到期前确认入库。\n'
       || E'- 退役到期自动清理：已退役的元素超过「退役条目保留天数」（默认 90 天）后将从账本中删除，其锚点命中统计与逐条操作记录一并清除；删除后该名称可被新元素重新使用。\n'
       || E'- 两个天数在「系统设置」里搜索 element.candidate-ttl-days（候选待验证时长上限）与 element.retention-days（退役条目保留天数）即可修改，取值范围 1-3650，保存后次日生效，无需重启。\n'
  WHERE domain_code = 'F'
    AND title = '页面元素库：Web 定位锚集中建档'
    AND body_md NOT LIKE '%自动留存清理（新增能力）%';
