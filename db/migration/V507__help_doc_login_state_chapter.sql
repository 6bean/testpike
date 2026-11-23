-- Migration: login-state ledger chapter of the app-automation help domain (X|93)
-- — the AV-batch facade door (capture / renew / status lights / honest guidance).
-- Four-section structure (what this page can do / how to use it / what the
-- system does behind the actions / common questions), per the batch ruling:
-- the FAQ must explain why captcha solving is out of scope, why a token that
-- works in your browser can fail on an executor, and how to capture an
-- SSO-redirect target.
-- Discipline: body carries no feature-point codes, task numbers, table names
-- or config keys (help-doc scanner gate). Idempotent via ON CONFLICT DO
-- UPDATE; sort_no 93 from the live X-domain scan (chapters 1..12 legacy,
-- 91=element library V472, 92=flow canvas V480); version ladder 3.04 -> 3.05.
INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no, version) VALUES
('X', '登录态账本：网页与接口的已登录身份台账', $mdq$,
## 这一页能做什么
登录态账本把「已经登录好的身份」存成可复用的资产：你在自己的浏览器里登录被测系统（含验证码、扫码、单点跳转等人工登录场景）后，平台把登录产生的会话（令牌、Cookie 集合或浏览器整存快照）采集为一行账本，供 API 测试集合、UI 用例执行、监控登录探针共同引用——不用再拿账号密码反复登录被测系统。哪条登录态还有效、还剩多久、能不能在执行机上重放，这一页一眼可查。

## 怎么用（按操作顺序）
1. **采集登录态**：点「采集登录态」，按提示在你自己的浏览器上发起采集，在打开的登录页里正常登录被测系统；系统判定登录成功后列出候选会话数据，勾选确认即入册。读取范围只限你在账本上申报过的来源域，申报之外的数据一个都不读。
2. **选形态**：向导按被测的鉴权方式给推荐——单点登录跳转的系统优先采「浏览器整存快照」（保住静默续登链路）；被测真的吃访问令牌时选令牌形态；浏览器里只看得到会话 Cookie 的选 Cookie 集合形态。
3. **续期与停用**：登录态是消耗品，同名重新采集＝覆盖更新那行（不追加新行）；同角色多个账号＝另起一个名字各立一行。停用一行后，引用它的任务在起跑预检时会被拒跑并写明原因。
4. **看状态灯**：四态分明——有效（带剩余时长）/ 即将到期 / 已失效（点名原因）/ 采集成功但执行机无法重放（疑 IP 或设备指纹绑定）。

## 操作背后系统做了什么
- 采集到的凭证一律加密托管，页面只回显引用键、来源域、有效期与状态灯，值永不上屏；被消费时由服务端统一解析注入，前端拿不到也带不走。
- 入册即从执行机的网络位置发一条验证请求做重放核验：浏览器位有效而执行机位无效的行，会被诚实标为「无法重放」并指路（改用执行机采集窗，或登记豁免声明）。
- 批次起跑前会预检本批用例要用的登录态，任一必需项失效就整批诚实拒跑，并点名「哪条登录态、到本页哪里续期」，不让几十条用例各自在登录页白白耗时。
- 登录态被消费时如果命中鉴权失败响应（401/403），系统会即时回写失效状态，状态灯与下次预检随之判定。

## 常见问题
- 为什么平台不能自动过验证码？——平台不实现任何验证码识别或绕过，唯一动作是复用服务端已签发的登录身份。被测带验证码的治本路径：在测试环境关闭验证码，或改用固定万能码、白名单账号，并到本页登记豁免声明、由系统复核为真。
- 令牌在我浏览器里好用，为什么执行机跑不通？——有些被测系统把会话绑到 IP 或设备指纹上，换机器携带就无效。入册后的重放核验会把这一行诚实标为「无法重放」；请改用执行机采集窗采一次，或按向导换采集形态再试。
- 单点登录跳转的系统怎么采？——登录成功判定锚填回跳后的地址；来源域清单把被测域与身份提供方两个域都填上；采集形态听向导推荐（整存快照优先，保住静默续登链路）。核验永远打被测的受保护地址，不会拿身份提供方地址误判失效。
- 状态灯显示「豁免声明未通过复核」怎么办？——你登记的环境声明（无验证码/万能码/白名单账号）与实测登录结果不符；先核对被测环境的实际设置，再重新发起复核。
$mdq$, 93, '3.05')
ON CONFLICT (domain_code, sort_no) DO UPDATE SET
  title = EXCLUDED.title,
  body_md = EXCLUDED.body_md,
  version = EXCLUDED.version,
  updated_at = now();
