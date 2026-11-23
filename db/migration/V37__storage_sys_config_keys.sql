
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('storage.provider',            's3', '存储', 'string', 0, '存储后端枚举 local/s3/aliyun-oss；s3=S3 兼容对象存储（SeaweedFS 网关或云厂商 S3 端点，地址与凭据在装机配置），枚举外的值将被拒绝'),
('storage.key-prefix',          'testpike', '存储', 'string', 0, '对象 key 统一前缀（StorageKeys 规范首段，IT-STO-04）'),
('storage.local-root',          '',      '存储', 'string', 0, '本地模式落盘根目录（storage.provider=local 必填；空=未配置）'),
('storage.url-ttl',             '15',    '存储', 'int',    0, '预签名时效分钟（TC-STO-03 到期 403 需重签；1~10080=7 天上限钳制）'),
('storage.oss.endpoint',        '',      '存储', 'string', 0, '阿里云 OSS endpoint（如 https://oss-cn-hangzhou.aliyuncs.com）'),
('storage.oss.bucket',          '',      '存储', 'string', 0, '阿里云 OSS bucket'),
('storage.oss.access-key-id',     '',    '存储', 'string', 1, 'OSS AK（加密存储，列表打码 TC-K8-01）'),
('storage.oss.access-key-secret', '',    '存储', 'string', 1, 'OSS SK（加密存储，DB 无明文 IT-K8-02）')
ON CONFLICT (config_key) DO NOTHING;
