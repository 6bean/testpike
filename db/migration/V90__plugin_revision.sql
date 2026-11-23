
ALTER TABLE tp_plugin ADD COLUMN IF NOT EXISTS revision INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN tp_plugin.revision IS '行级乐观锁版本（迁移件 PUT 编辑；version 列=语义版本正交不复用，冲突 40901 可读点名当前值）';
