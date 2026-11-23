-- Migration V607: help-chapter sync for the extraction-preview gap banner
-- (WR batch behavior change: uncovered chunks now carry a reason, readable
-- neighbour context, and a one-click directed supplement action).
--
-- Why this edit exists:
-- - changed what the preview dialog does when some chunks produced
--   zero requirement points: the banner links each chunk id to a viewer that
--   answers WHY (ledger reason card), shows the chunk inside its surrounding
--   original text (adjacent-chunk area, budgeted by ai.doc.chunk-context-chars),
--   R61 requires the help chapter to be updated in the same batch as the
--   behavior change, written in the user's point of view with the exact
--   on-screen literals quoted.
--
-- Shape: append-style UPDATE with a NOT LIKE guard (V539/V597 precedent) —
-- zero writes on replay, composes with other pending appends to this chapter.

UPDATE tp_help_doc
   SET body_md = body_md || '

## 提取预览的「未覆盖分块」提示怎么用
一轮提取跑完，如果有分块没产出需求点，预览表格上方会出现黄色提示条「本次提取有 N 个文档分块未产生需求点，点击分块号查看原文、原因与「补提此块」入口」。分块号就是按钮，点开一个窗口，一次看清三件事：
- **为什么没提出来**：顶部原因卡给出系统记录的原因，例如「本块正文仅 X 字」——被切成薄块的小段落通常不含独立需求点；没有记录具体原因时显示「账本未记录具体原因，可发起补提此块验证」，直接发起一次补提验证即可。
- **原文长什么样**：正文头标注「本文档第 N 块 · 本块正文 X 字」，「相邻原文（M 块）」区默认展开，带出前邻块的原文——薄块单独看容易误判漏没漏，配上前文一眼就能定；邻块行上的「查看该块上下文」还能继续往前看，上下文区末尾有「本块（第 N 块）正文见上方」的回标提示。
- **下一步做什么**：底部「补提此块」按钮只对当前这一个分块发起一轮定向提取，点了会提示「已发起定向补提，本轮仅处理该分块，完成后本页自动更新」。新产出的结果直接出现在预览列表里，和以往一样逐条勾选后才入库；看完判断确实没有漏，放着不动也行，提示不阻塞任何操作。
提示条下方若还有「尚未覆盖的维度：」，那是清单整体缺某一类需求点时的定向补充入口，点一下即可发起该维度的补充提取——已有条目保留，新结果同样要逐条确认。

- 补提会把整篇文档重新提取一遍吗？——不会。「补提此块」只处理当前打开的那个分块；同一文档已有提取在跑时会提示「该文档的需求点提取正在进行中，请等待完成后再试」，等完再点即可。文档重新解析后旧分块号会失效，按提示重新打开「提取预览」里的最新分块再发起就行。
- 为什么有的分块没产出需求点？——最常见是被切成只有一句话的薄块，或那段本来就是排版性内容（目录、页眉、附录清单）。先看原因卡，再翻「相邻原文」判断：确属漏提就点「补提此块」；确属噪音放着即可，不影响其余条目的确认入库。
- 上下文能带多少前文？——带的前邻块有总字数预算，预算不够时最远的邻块先不带，页面上仍保留逐块「查看该块上下文」的入口，逐块往前走总能看到。',
       updated_at = now()
 WHERE domain_code = 'F' AND title = '上传文档，自动提取需求点'
   AND body_md NOT LIKE '%未覆盖分块%';
