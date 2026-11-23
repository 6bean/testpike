
UPDATE tp_help_doc
   SET body_md = body_md || '

## 立即测试
列表每行有「立即测试」：填目标系统地址（留空=自动取绑定登录态的作用源），
可改请求方式并在请求体里写占位符 {{CRED:引用键}}。点开始后，过程流逐行给出
探测目标、构造请求（凭据材料恒打码，实发为真值）、HTTP 应答与判定；
目标接受时自动盖「上次验证」时刻。'
 WHERE domain_code = 'Z' AND title LIKE '凭据保险箱%'
   AND body_md NOT LIKE '%立即测试%';

UPDATE tp_help_doc
   SET body_md = body_md || '

## 立即测试
列表每行有「立即测试」：用账本里的会话快照向目标源重放一次真实请求
（留空=第一条登记作用源，也可手动指定受保护地址）。过程流逐行给出
探测目标、构造请求（会话值恒打码）、HTTP 应答与判定：
2xx=会话仍在，重定向到登录页或 401/403=需要重新采集。有效判定自动盖「上次验证」时刻。'
 WHERE domain_code = 'Z' AND title LIKE '登录态账本%'
   AND body_md NOT LIKE '%立即测试%';

UPDATE tp_help_doc
   SET body_md = replace(body_md,
       '步骤按名引用，运行时注入、日志与截图自动打码。',
       '步骤按名引用，运行时注入、日志与截图自动打码；行内「立即测试」可对目标系统实发验证并看全过程流。')
 WHERE domain_code = 'Z' AND sort_no = 1 AND body_md LIKE '%凭据保险箱%'
   AND body_md NOT LIKE '%行内「立即测试」%';

UPDATE tp_help_doc
   SET body_md = replace(body_md,
       'API/UI 用例与监控共用，失效点名指路。',
       'API/UI 用例与监控共用，失效点名指路；行内「立即测试」重放会话到目标源，判定与全过程流可读。')
 WHERE domain_code = 'Z' AND sort_no = 1 AND body_md LIKE '%登录态账本%'
   AND body_md NOT LIKE '%重放会话到目标源%';
