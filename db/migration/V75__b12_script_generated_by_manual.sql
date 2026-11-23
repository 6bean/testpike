ALTER TABLE tp_script DROP CONSTRAINT ck_script_generated_by;
ALTER TABLE tp_script ADD CONSTRAINT ck_script_generated_by CHECK (generated_by IN (1, 2, 3, 4));
