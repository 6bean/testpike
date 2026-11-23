-- Migration V571: R61 help-chapter amendment for the exchange-trail reading
-- doors on the two test-now entries . The process
-- drawer now shows the full request/response exchange per step, masked secret
-- values with a gated reveal door and paged full-text reading, so the two
-- chapters describing those entries must state in the same batch how to read
-- the exchange, why values are masked and where to adjust the budgets.
-- Idempotent: each append is guarded by NOT LIKE on its marker heading, a
-- replay after the amendment writes zero rows.

UPDATE tp_help_doc
   SET body_md = body_md || E'\n\n## 过程里的请求与返回怎么看\n'
       || E'点「立即测试」后，页面下方打开「过程详情（实时）」抽屉跟随进度滚动；每一步点「展开」可看到完整交换数据：请求行（方法、地址）、请求头、状态码、响应头（含 Set-Cookie）、响应体。\n'
       || E'- 会话值、令牌等敏感内容以打码形式显示（前 8 个字符 +「总长度 N」）；点「显示明文」当场取回真实值，每次读取都会留一条审计记录。「复制原文」按钮只复制打码文本，明文不提供一键复制。\n'
       || E'- 内容超出显示上限时末尾出现「共 N 字节，展开全文」，点开后分页续读，点「继续加载」直到「已读取全部」。两个上限可在系统设置中调整（参数 ui.tail.detail-max，默认 16384 字符；session.probe.body-max-bytes，默认 65536 字节；保存即生效）。\n'
       || E'- 向上滚动会暂停跟随（显示「已暂停跟随，正在回看历史」），点「跳到最新」恢复；「显示明文」若提示「明文读取失败，请重新执行一次测试后再试」，重新执行一次测试即可。\n'
       || E'- 常见问题：没等到响应体那一行？探测失败或目标无应答时对应行仍会落账，判定行写明原因；打码值看着像被截断？打码只是显示形态，重放与测试用的都是完整值。\n'
  WHERE id = 565
    AND body_md NOT LIKE '%过程里的请求与返回怎么看%';

UPDATE tp_help_doc
   SET body_md = body_md || E'\n\n## 过程里的请求与返回怎么看\n'
       || E'点「立即测试」后，页面下方打开「过程详情（实时）」抽屉跟随进度滚动；每一步点「展开」可看到完整交换数据：请求行（方法、地址）、请求头、状态码、响应头、响应体。\n'
       || E'- 凭据实值与鉴权头以打码形式显示（前 8 个字符 +「总长度 N」）；点「显示明文」当场取回真实值，每次读取都会留一条审计记录。「复制原文」按钮只复制打码文本，明文不提供一键复制。\n'
       || E'- 内容超出显示上限时末尾出现「共 N 字节，展开全文」，点开后分页续读，点「继续加载」直到「已读取全部」。两个上限可在系统设置中调整（参数 ui.tail.detail-max，默认 16384 字符；session.probe.body-max-bytes，默认 65536 字节；保存即生效）。\n'
       || E'- 向上滚动会暂停跟随（显示「已暂停跟随，正在回看历史」），点「跳到最新」恢复；「显示明文」若提示「明文读取失败，请重新执行一次测试后再试」，重新执行一次测试即可。\n'
       || E'- 常见问题：请求里没看到凭据实值？凭据值在请求体引用 {{CRED:引用键}} 时才注入，注入后发的是真值，过程流里显示恒为打码形态。\n'
  WHERE id = 453
    AND body_md NOT LIKE '%过程里的请求与返回怎么看%';
