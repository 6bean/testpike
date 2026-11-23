
ALTER TABLE tp_tool
    ADD COLUMN IF NOT EXISTS download_count INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN tp_tool.download_count IS
    '市场下载量（v2.16 U1 市场三件套；TC-U-05/IT-TOOL-05 涨量断言源；presign 签发一次 +1，UPDATE 原子自增，V41 同构）';
