-- Migration V581: R61 help-chapter amendment for the credential vault test
-- door . The dialog gained repeatable request-header rows, a
-- one-click prefill and a four-state evidence grade that rides on the list
-- column; the chapter must state in the same batch how to read the four
-- verdicts and where the fix-it pointer for the grey/yellow states leads.
-- Idempotent: the append is guarded by NOT LIKE on its marker heading — a
-- replay writes zero rows. Anchor id 453 = live-scan of the credential
-- vault chapter. Body carries no task numbers, table names or config keys
-- (help-doc scanner gate); section names follow the user-voice mapping.
UPDATE tp_help_doc
   SET body_md = body_md || E'\n\n## 立即测试的四种结果怎么看\n'
       || E'列表每行的「验证结果」列和测试弹窗底部的判定用同一套四态徽标：\n'
       || E'- 凭据已验证（绿）：目标明确接受了这份凭据。\n'
       || E'- 凭据被拒（红）：目标明确拒绝，例如返回 401/403。\n'
       || E'- 凭据未被检验（灰）：这一趟实际没有携带凭据——请求行、请求头、请求体三处都没有引用它。页面会直说这一点，点「用此凭据」再跑一次即可让判定真正生效。\n'
       || E'- 结果需人工复核（黄）：目标回了 200，但回来的内容更像登录页壳——单页应用常在不带有效凭据时这样做。平台不武断判失效，请到「过程详情」里看一眼交换数据再定。\n'
       || E'「用此凭据」按凭据类型自动预填请求头（令牌类填 Authorization，会话类填 Cookie），预填后可以再改；请求头行还支持占位符与脚本变量，可逐行增删。判定说明里的引用计数会把地址、请求头、请求体三处的注入点一起对上账，没注入就是没注入，徽标不会假装通过。\n'
  WHERE id = 453
    AND body_md NOT LIKE '%立即测试的四种结果怎么看%';
