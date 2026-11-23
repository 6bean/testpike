-- Help chapter for the dead-letter ops page leaf .
-- Why: the menu leaf '/mq-dead-letter' rides domain K (multi-chapter), so the
-- per-leaf anchor contract (menuHelpCoverage) requires its own chapter —
-- without one its "?" would land on another chapter and the coverage would be
-- fake. Mirrors the V251/V252/V254 precedent.
-- Discipline: body carries zero table names / config keys / feature codes;
-- idempotent via ON CONFLICT (domain_code, sort_no) DO UPDATE.
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike -v ON_ERROR_STOP=1 -f - < this file

INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no, version, updated_at)
VALUES
('K', '死信队列：重试耗尽的消息在这里查原因', $mdq$
## 这一页能做什么
消息投递重试次数用完后会进入死信队列。本页把这些消息集中列出来：按队列分组看积压有多少、按失败原因分型看各自占多少、逐条查看消息原文与失败说明，方便定位上游服务、配置或缺失数据的问题。

## 怎么用
1. 顶部筛选：按处理状态、队列、失败分型组合筛，点「查询」生效；点「刷新」重新取数。
2. 「按队列分组积压」表点某一行，下方明细列表即收窄到该队列；「失败分型分布」面板同步联动。
3. 明细行点「查看」弹出消息体原文（JSON 缩进展示）；「重放轮次」表示这条消息已被自动或人工重放过几轮。
4. 分型为「实体已删除」的消息会标注「禁重放」——对应的业务数据已经不在了，重放没有意义，可等待后续批量作废处理。
5. 积压条数超过提醒阈值时页面顶部出现晕染提醒行；系统同时会发站内通知（如已订阅）。

## 操作背后系统做了什么
每条进死信的消息先记录到账、再向消息服务器确认，因此这里的积压数字始终是「已记录待处理」的条数，不代表消息服务器队列的实时深度。失败分型由服务端统一判定，页面只做展示与筛选，不做二次判断。手动重放、批量重放与作废入口在后续版本开放，开放后每一步都走人工确认。

## 常见问题
- 为什么数字和消息服务器管理台看到的对不上？——口径不同：本页只算已记录且处于该状态的消息，消息服务器队列里的原始条目确认后即移出。
- 「未分型」是什么？——没有命中任何已知失败特征的兜底标注，表示需要人工看一眼原因文本，不会误判成其他类型。
- 处理完的消息还会显示吗？——切换状态筛选到「已重放」或「已作废」即可查看历史处理记录。
$mdq$,
6, '2.177', now())
ON CONFLICT (domain_code, sort_no) DO UPDATE
  SET title = EXCLUDED.title,
      body_md = EXCLUDED.body_md,
      version = EXCLUDED.version,
      updated_at = now();
