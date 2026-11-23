-- Migration V574: R61 help-chapter amendment for the identity doors of the
-- web exploration create dialog . The dialog now offers
-- credential vault — two namespaces, so the chapter must state in the same
-- batch which identity each door carries and where to fix an expired state.
-- Idempotent: the append is guarded by NOT LIKE on its marker heading, a
-- replay after the amendment writes zero rows.

UPDATE tp_help_doc
   SET body_md = body_md || E'\n\n## 探索用哪个身份进门\n'
       || E'新建网页探索时，起始 URL 下方有「登录态（可选）」下拉：候选=本项目已登记的登录态（访问令牌 / Cookie / 浏览器快照三种重放方式），选中后探索浏览器以该身份直接进入站点，不用在探索里重新登录；不选=匿名探索，遇到需要登录的页面会如实留在登录页。\n'
       || E'- App 形态的「预置测试账号」和网页形态的「登录态」是两套来源：前者引用凭据保险箱里的账号（脚本内登录 App 用），后者是浏览器侧的登录材料（网页探索重放用）；两者互不替代，口令与 Cookie 实值都不随探索任务下发。\n'
       || E'- 下拉里没有候选？先到「登录态账本」页登记一条或从已登录浏览器采集一份，回到本页重新打开新建弹窗即可看到。\n'
       || E'- 探索过程仍被挡在登录页？多半是该登录态已过期：到「登录态账本」页对它「立即测试」确认失效，重新采集后再发起探索即可；会话材料过期不会被静默冒充成功。\n'
  WHERE id = 563
    AND body_md NOT LIKE '%探索用哪个身份进门%';
