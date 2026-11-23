
ALTER TABLE tp_skill
    ADD COLUMN IF NOT EXISTS download_count INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN tp_skill.download_count IS
    '市场下载量（v2.16 S3 用量三列①；TC-S-03 涨量断言源；presign 签发一次 +1，UPDATE 原子自增）';
