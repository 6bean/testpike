-- V472: help chapter for the element library page (/appauto/elements) in the
-- app-automation domain X. The leaf carries a chapter anchor
-- chapter that teaches what the page does: locator profiles kept once and
-- referenced by name from app steps. Idempotent: (domain_code, sort_no)
-- unique key, ON CONFLICT DO NOTHING.
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < db/migration/V472__help_doc_element_library_chapter.sql

INSERT INTO tp_help_doc (domain_code, title, body_md, sort_no, version) VALUES
('X', '元素库：定位方式存成档案，步骤只引用不重复配', $mdq$## 元素库

元素库把 APP 界面上要操作的控件（按钮、输入框、区域）的定位方式存成档案。步骤里只按元素名称引用，不各写一份定位参数：控件换了位置或换了 id，改档案一处，所有引用它的步骤下次执行即生效。

| 列 | 一句话说明 |
| --- | --- |
| 元素名称 | 项目内唯一，步骤按这个名字引用 |
| 定位方式 | IMAGE（图像匹配）/ COORD（坐标）/ REGION（区域）/ OCR（文字识别） |
| 定位参数 | 一组 JSON 参数，例如资源 id、匹配文本、坐标或图片归档号 |
| 置信阈值 | 图像/OCR 匹配的最低分数（0 到 1），低于阈值按定位失败处理 |
| 分辨率变体 | 同一元素在不同分辨率屏幕上的备选取图，执行时按设备分辨率优先匹配 |

## 怎么用
1. 顶栏选中项目，本页只显示该项目的元素档案；新建/编辑/删除需要该项目的管理权限。
2. 点「新建元素」，先选定位方式，再按 JSON 填写定位参数；格式或取值不合规时保存会被拒绝并提示具体原因。
3. 列表按名称即时搜索、按定位方式筛选，数据多时翻页查看；页态记录在地址里，刷新或分享链接后筛选和页码保持不变。
4. 定位的实际查找与容错由执行侧统一处理，本页只维护档案内容本身。

**常用入口**：左侧菜单「APP 自动化 → 元素库」。$mdq$, 91, '2.99')
ON CONFLICT (domain_code, sort_no) DO NOTHING;
