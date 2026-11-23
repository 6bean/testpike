-- Help chapter for the AI chat page .
-- Why: the standalone chat page joined the menu as an AI-capability leaf whose
-- help entry points at the domain code 'AI'; that domain had no chapter yet, so
-- the "?" would fall back to a bare code. One chapter in the new domain covers
-- both entry surfaces (the floating dock and the full page) since they share
-- the same conversation kernel.
-- Discipline: body carries zero table names / task codes / internal governance
-- words; four-section page contract (what this page does / how to use / what
-- the system does behind it / common questions); idempotent via
-- ON CONFLICT (domain_code, sort_no) DO NOTHING. Mirrors the V256 pattern.
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike -v ON_ERROR_STOP=1 -f - < this file

INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no, version, updated_at)
VALUES
('AI', 'AI 对话：随时提问，全程可见', $mdq$
## 这一页能做什么
直接向 AI 提问当前项目里的事：分析某次执行为什么失败、查一批数据、起草一段内容。两个入口背后是同一套对话能力——右下角悬浮气泡拉开的是侧挂对话坞（适合干活时随手一问）；「AI 能力 → AI 对话」是独立整页（适合连续多轮深聊）。

## 怎么用
1. 先在顶栏选好项目：对话始终围绕当前项目展开，未选项目时发送钮会说明原因。
2. 输入问题后回车或点「发送」：回答逐字流出，上方的「分析过程」区折叠展示 AI 查了哪些数据、走了哪些步骤，点开可看全部。
3. 回答不对路可点「停止」中止本轮；网络中断后会出现「继续」按钮，从断点接着收，不会重复执行已经跑过的动作。
4. 想改问题直接再发一条；切换顶栏项目等于换了一个上下文，对话自动开新会话。

## 操作背后系统做了什么
每个问题由服务端统一编排：先核对你的账号对该项目的访问范围，再按白名单读取数据交给模型；模型边生成边把过程帧记账，界面据此回放与断点续传。涉及改动的动作一律先出草案、由你确认后才执行，未经确认不会落地。

## 常见问题
- 发送钮点不动？——顶栏还没选项目，选一个即可，提示会点名缺什么。
- 回答停在一半？——点「继续」从断点接上；长时间无响应会给出中断提示，不会无限等待。
- AI 能看到我全部数据吗？——只能看到你当前项目内、你账号有权访问的内容，范围由服务端每次校验。
$mdq$,
1, '2.95', now())
ON CONFLICT (domain_code, sort_no) DO NOTHING;
