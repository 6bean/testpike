
ALTER TABLE tp_document
    ADD COLUMN IF NOT EXISTS extraction_json JSONB,
    ADD COLUMN IF NOT EXISTS extract_error   VARCHAR(500);

COMMENT ON COLUMN tp_document.status IS
    '0=解析中 1=解析成功 2=解析失败 3=提取中 4=已提取（01 v2.11 F2 扩展位）';
COMMENT ON COLUMN tp_document.extraction_json IS
    'F2 提取预览清单 [{id,title,acceptanceCriteria,chapter,confidence,selected,requirementId}]（T131）';
COMMENT ON COLUMN tp_document.extract_error IS
    'F2 提取失败可读（status 3→1 回退时写入；与解析域 error_msg 分离，tick 62 设计决策）';

