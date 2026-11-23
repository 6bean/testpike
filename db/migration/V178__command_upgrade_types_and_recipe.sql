
ALTER TABLE tp_cluster_command DROP CONSTRAINT IF EXISTS ck_cluster_command_type;
ALTER TABLE tp_cluster_command
    ADD CONSTRAINT ck_cluster_command_type CHECK
    (command_type IN ('engine-install', 'engine-uninstall', 'engine-upgrade',
                      'engine-rollback', 'scale-in', 'node-remove'));
COMMENT ON COLUMN tp_cluster_command.command_type IS
    '命令型值域（CHECK 六枚：V172 四枚+升级两枚，扩型必同步 03 §2.9）';

UPDATE tp_appauto_engine
SET install_recipe = jsonb_set(
        install_recipe,
        '{hosts,macos,upgrade}',
        '[{"cmd": "appium driver remove xcuitest"},
          {"cmd": "appium driver install xcuitest@12.15.0"}]'::jsonb,
        true),
    updated_at = now()
WHERE code = 'XCUITEST'
  AND install_recipe #> '{hosts,macos,upgrade}' IS NULL;
