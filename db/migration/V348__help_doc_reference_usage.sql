-- Help-center chapters for per-document usage control (reference documents).
--
-- Why this file exists:
--   Documents now carry a category (test spec / data dictionary / reference)
--   and three per-document usage switches (extraction / case-prompt /
--   grounding) editable inline on the requirement-extraction page. Users
--   asking "how do I use a reference document" need one chapter per relevant
--   domain: D (feedback & optimization, the prompt channel) and G (knowledge
--   base, the retrieval channel). Without these rows the "?" entry on those
--   surfaces would show no guidance for the new controls.
-- Discipline: body text carries zero table names / task codes / internal
--   governance words; operator-facing information only (defaults, scope,
--   what changes when a switch flips, where to click). Four-section layout
--   mirrors the V297 precedent. Version follows the PRD registration row of
--   the feature (v2.141).
-- Idempotent: ON CONFLICT (domain_code, sort_no) DO NOTHING; body uses
--   $mdq$ dollar-quoting to survive quotes.
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike -v ON_ERROR_STOP=1 -f - < this file

INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no, version, updated_at)
VALUES
('D', '参考文档怎么用：让好用的资料进入用例生成', $mdq$
## 这个能力做什么
每份上传的文档都能单独设置它参与哪几条链路：需求点提取、测试用例生成的提示词、在线 AI 对话的检索参考。「参考文档」分类专放纯资料——不当需求源料，但可以当写用例和问答的素材。

## 怎么用
1. 入口在「设计用例 → 需求提取」页的「文档」页签，每行的「参与用途」有三个勾选：提取、用例、接地。
2. 上传文档时可以选「参考文档」分类：这类文档默认不参与需求提取，默认参与用例生成和对话检索；需要它也进提取时，勾上那行的「提取」即可。
3. 勾选随时可改，改完立即保存，只影响这一份文档，其他文档不受牵连。
4. 分类选错了不用重新上传：点行内分类名直接改，文档内容和已切分的检索块都保留。

## 操作背后系统做了什么
- 生成测试用例时，只有勾了「用例」的文档，其内容才会作为参考资料注入提示词；没勾的文档不会参与这一轮生成。
- 关闭某个勾选只作用于之后的操作：已经提取入库的需求点、已经生成的用例、历史对话的引用记录都保持不变，不会被改动或删除。

## 常见问题
- 一份文档想既作检索参考又进用例生成？——把「用例」和「接地」都勾上，这正是「参考文档」分类的默认状态。
- 关掉「用例」后之前生成的用例会消失吗？——不会。开关只管之后的生成动作，历史产物不动。
$mdq$,
4, '2.141', now()),

('G', '参考文档怎么用：让 AI 对话查到对的资料', $mdq$
## 这个能力做什么
控制在线 AI 对话回答问题时能检索到哪些文档：每份文档的「接地」勾选决定它是否进入对话的检索范围，配合项目与账号权限一起把答案锁定在你该看到的资料内。

## 怎么用
1. 「设计用例 → 需求提取」页的「文档」页签，每行「参与用途」中的「接地」勾选=在线 AI 对话能否检索这份文档。
2. 只想让资料当问答素材、不想让它被当成需求源料的，上传时选「参考文档」分类：它默认参与对话检索、默认不进需求提取。
3. 「知识库」台账页可查看各文档切分出的内容块与它们被引用的情况。

## 操作背后系统做了什么
- 对话每轮回答前，服务端先按「接地」勾选、当前项目切片和你的账号权限范围筛掉不该被引用的文档，再把命中的内容片段交给模型作答。
- 关闭「接地」后，新的回答不再引用该文档；此前对话留下的引用记录保留，可继续追查。

## 常见问题
- 对话没有引用我期待的参考文档？——先确认该行「接地」已勾选，再确认顶栏选中的项目就是这份文档所在项目。
- 长文档会把全文塞给模型吗？——不会。文档按内容块切分，只有与问题最相关的块会进入本轮回答。
$mdq$,
3, '2.141', now())
ON CONFLICT (domain_code, sort_no) DO NOTHING;
