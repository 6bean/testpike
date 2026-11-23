
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('kb.reweight-excellent', '1.2',  '知识库', 'string', 0, '优秀用例来源重排加权（G2/T178 14 §5.2；仅 source_type=TEST_CASE；域 1.0~2.0 越界回退配置文件）'),
('kb.reweight-spec',      '1.1',  '知识库', 'string', 0, '测试规范/行业包语料/风格来源加权（G2/T178 14 §5.2；TEST_SPEC/PACK_KNOWLEDGE/STYLE；域 1.0~2.0）'),
('kb.reweight-feedback',  '0.8',  '知识库', 'string', 0, '反馈样本来源加权（G2/T178 14 §5.2；FEEDBACK；域 0.0~1.0）'),
('kb.lang-bonus',         '0.05', '知识库', 'string', 0, 'lang 与项目默认语言一致加分（G2/T178 14 §5.2；域 0.0~0.5）'),
('kb.fusion-mode',        'rrf',  '知识库', 'string', 0, '双路融合模式 rrf|weighted（G2/T178 14 §5.1；白名单外回退 rrf）'),
('kb.global-weight',      '0.3',  '知识库', 'string', 0, 'weighted 模式全局链路权重（G2/T178 14 §5.1 项目内 0.7/全局 0.3；域 0.0~0.5）')
ON CONFLICT (config_key) DO NOTHING;
