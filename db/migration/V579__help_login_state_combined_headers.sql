-- Migration V576: R61 help-chapter amendment for the login-state ledger
-- . The create door now carries a combined-session
-- header list; the chapter must state in the same batch how to register one,
-- why values never appear on the row, and which cap applies. Idempotent:
-- the append is guarded by NOT LIKE on its marker heading — a replay writes
-- zero rows. Anchor id 565 = live-scan of the login-state ledger chapter
-- (menu door: data-factory & assets -> login-state ledger; the domain-X row
-- originally inserted by an earlier chapter migration was later re-homed).
-- Body carries no task numbers, table names or config keys
-- (help-doc scanner gate), section names follow the user-voice mapping.
UPDATE tp_help_doc
   SET body_md = body_md || E'\n\n## 一个身份带多把钥匙（附加会话头）\n'
       || E'有的站点一个已登录会话要同时带两把钥匙：例如主令牌走 Authorization 头，另一把 API Key 走自定义头。新建登录态时，「登录类型」下方的「附加会话头」区可以把后一类头一并登记：每行选一条本项目凭据保险库中已建档的凭据，填写头名称（如 X-API-KEY），可选填值前缀模板（如 Bearer 加引用占位符）。执行与「立即测试」时由服务端在发送前一刻组装，效果与不带附加头的登录态完全一致。\n'
       || E'- 为什么界面上看不到口令实值？附加会话头只存引用不存值：实值加密留在凭据保险库，重放时即时装配、全程打码展示，需要核对时按既有权限单独解密查看并留审计——登录态这一行被分享或导出也带不走任何密钥。\n'
       || E'- 能带几条？出厂上限 8 条（可配置调高），超出会被拒绝并提示当前上限；同一凭据在同一登录态里不能重复挂两次，也不能与登录类型自带的主头同名，保存时会被点名拒绝并说明怎么改。\n'
       || E'- 同名重新采集会覆盖登录材料，但不会清掉你手工登记的附加会话头；想调整附加头，在新建弹窗里填一份完整清单即可整体替换。\n'
  WHERE id = 565
    AND body_md NOT LIKE '%一个身份带多把钥匙%';
