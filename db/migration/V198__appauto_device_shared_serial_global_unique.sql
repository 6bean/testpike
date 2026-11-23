
ALTER TABLE tp_appauto_device ALTER COLUMN project_id DROP NOT NULL;

ALTER TABLE tp_appauto_device DROP CONSTRAINT uq_appauto_device_serial;
ALTER TABLE tp_appauto_device ADD CONSTRAINT uq_appauto_device_serial UNIQUE (serial);

COMMENT ON COLUMN tp_appauto_device.project_id IS '归属项目；NULL=跨项目共享资产（全员项目可见可占，写面 ADMIN 独占）；serial 全局唯一（迁移件）';
COMMENT ON CONSTRAINT uq_appauto_device_serial ON tp_appauto_device IS '序列号全局活跃集唯一（共享位；禁跨项目重复建档=租约双记账）';
COMMENT ON COLUMN tp_appauto_lease_run.project_id IS '占用发生的项目（借用方归属，非设备归属项目）';
