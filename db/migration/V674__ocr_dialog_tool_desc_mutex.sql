-- Dialog OCR tool descriptions: name the sibling post and forbid calling both
-- for the same image .
--
-- Context: the model routes between the character post (ocr.text_extract) and
-- the grid post (ocr.grid_read) purely by these catalog descriptions. A live
-- observation recorded both posts being called successfully in the same turn
-- for one image: harmless but a redundant extra
-- engine round trip. The seed initializer only registers missing rows and
-- never overwrites, so this UPDATE brings already-seeded databases to the
-- same mutually-exclusive wording the initializer now ships for fresh
-- installs, matching by exec_spec intent instead of a per-install id.
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production).

UPDATE tp_tool
   SET description = '逐行提取会话图片中的文字：每行给出内容、位置框与置信标注，标红行会单独点名；适合把截图里的文字原样抄出来（包括非表格的截图）。两个工具二选一：需要把截图装配成行列表格时改用 ocr.grid_read，同一张图片只调用其中一个',
       updated_at = now()
 WHERE exec_spec ->> 'intent' = 'ocr.text_extract';

UPDATE tp_tool
   SET description = '读取会话中表格或面板截图的行列内容：先识别文字再装配成行列表格，返回表格明细与网格判定；适合看表格、表单类截图里的具体数值。两个工具二选一：只需要抄出全部文字时改用 ocr.text_extract，同一张图片只调用其中一个',
       updated_at = now()
 WHERE exec_spec ->> 'intent' = 'ocr.grid_read';
