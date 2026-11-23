
UPDATE tp_sys_config SET config_value = 's3'
 WHERE config_key = 'storage.provider' AND config_value = 'minio';

UPDATE tp_sys_config SET description =
  '存储后端枚举 local/s3/aliyun-oss；s3=S3 兼容对象存储（SeaweedFS 网关或云厂商 S3 端点，地址与凭据在装机配置），枚举外的值将被拒绝'
 WHERE config_key = 'storage.provider';

COMMENT ON TABLE tp_appauto_app IS '被测应用档案（包名/版本/APK 对象存储工件引用/权限规则集引用）';
COMMENT ON COLUMN tp_appauto_app.apk_artifact_key IS 'APK 工件的对象存储 object key（launch 动作 install/覆盖安装/清数据的工件源）';
COMMENT ON TABLE tp_mock_recording IS 'Recording cassette box (proxy traffic captured behind the allowlist gate; cassette body stored in object storage, this row keeps stats + reference)';
COMMENT ON COLUMN tp_mock_recording.cassette_ref IS 'Object-storage artifact reference of the cassette body';
COMMENT ON COLUMN tp_mock_llm_cache.artifact_ref IS 'Object-storage reference for oversized generated bodies (cache rows stay metadata-light)';
COMMENT ON TABLE tp_login_state IS 'Login-state ledger (AV2): one row per account login state captured or produced for a project; secret material stays in tp_credential (single vault), snapshots ride object storage state_object_key scoped to the project';
COMMENT ON COLUMN tp_login_state.state_object_key IS 'Object-storage object key of the storageState snapshot (project-level scope; NULL for non-snapshot kinds)';
