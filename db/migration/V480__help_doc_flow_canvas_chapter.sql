-- V480: help chapter for the UI Flow canvas page (/appauto/flow-canvas) in the
-- app-automation domain X. The leaf carries a chapter anchor
-- chapter that teaches what the page does: an APP case step tree projected onto
-- a read-only flowchart, with clicked nodes mapped back to steps through the
-- server-emitted pairing. Idempotent: (domain_code, sort_no) unique key,
-- ON CONFLICT DO NOTHING.
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < db/migration/V480__help_doc_flow_canvas_chapter.sql

INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no, version) VALUES
('X', '流程画布：把用例步骤投影成流程图，点节点定位步骤', $mdq$## 流程画布

流程画布把一个 APP 用例的步骤树投影成只读流程图：步骤按执行顺序连线，条件与循环的分支在图上可见。画布只做查看与定位，不支持拖拽编辑；要改步骤，用步骤卡里的「打开用例」回到用例步骤树。

| 区域 | 一句话说明 |
| --- | --- |
| 用例选择 | 输入关键字搜索并选中该项目可见的 APP 用例 |
| 流程画布 | 步骤序列投影出的流程图，点击节点高亮对应步骤 |
| 步骤卡 | 显示选中节点的步骤序号与动作摘要 |
| 打开用例 | 跳到该用例的步骤树继续编辑 |
| 去元素库取图 | 打开元素库页，为该步骤补齐图像定位素材 |

## 怎么用
1. 顶栏选中项目，用例搜索只返回该项目可见的用例。
2. 选中用例后流程图自动渲染；图太大或太小时用画布右上角的放大、缩小、复位按钮调整，缩放只影响当前查看。
3. 点击画布节点，下方步骤卡给出该步骤的序号和动作；需要修改内容时点「打开用例」跳转。
4. 选中的用例记录在地址里（caseId），刷新或分享链接后直接回到同一用例的流程视图。
5. 流程结构与节点对应关系由服务端给出，画布不重复判断步骤语义；服务端未返回映射的节点不会出现在画布上。

**常用入口**：左侧菜单「APP 自动化 → 流程画布」。$mdq$, 92, '2.99')
ON CONFLICT (domain_code, sort_no) DO NOTHING;
