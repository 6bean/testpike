-- V391: AX batch per-domain upgrade group 3 (second half) — the
-- autonomous exploration loop leg joins the shared behaviour kernel (L0 head
-- assembly via BehaviorKernelAssembly in code). This leg is dual-modality, so
-- the static contract splits into two template families: web
-- (explore-web-directives) and mobile (explore-mobile-directives). The
-- per-session goal line stays code-side and is appended after the assembled
-- head, so an operator rewording the template can never drop the run's
-- context; the GOAL_REACHED declaration sentence is carried verbatim in the
-- seed rows because the loop reads that marker to terminate on goal reached.
-- * governance keys ai.explore.web-directives.enabled /
--   ai.explore.mobile-directives.enabled: factory default on (AI features
--   ship enabled; only an explicit 0/false closes the operator-tuned
--   directive read; a missing row keeps directives on).
-- * seed rows: each modality's exploration contract, carried verbatim from
--   the built-in fallback head; the failure-transparency and evidence-first
--   invariant is appended by the kernel piece in code and is not editable
--   here.
-- All inserts are replay-safe (ON CONFLICT DO NOTHING).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.explore.web-directives.enabled', 'true', '系统', 'bool', 0,
     'AI 网页自主探索循环行为指令开关（默认开启；开启时探索指令采用「提示词模板」页面中 explore-web-directives 的已发布内容，为保目标达成判定生效，模板内容必须保留含契约位 GOAL_REACHED 的宣告句；关闭或模板没有已发布内容时，按内置探索指令执行，探索任务不中断。修改即时生效，无需重启）'),
    ('ai.explore.mobile-directives.enabled', 'true', '系统', 'bool', 0,
     'AI 移动应用自主探索循环行为指令开关（默认开启；开启时探索指令采用「提示词模板」页面中 explore-mobile-directives 的已发布内容，为保目标达成判定生效，模板内容必须保留含契约位 GOAL_REACHED 的宣告句；关闭或模板没有已发布内容时，按内置漫游指令执行，探索任务不中断。修改即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;

INSERT INTO tp_prompt_template (name, domain, version, content, status, created_by) VALUES
('explore-web-directives', 'explore-web-loop', 1,
$$你是自主测试探索代理。
每轮至多选择一个工具动作发起 tool_call（内置动作：page-snapshot 读页面快照、click-path 点击路径、input-path 输入、assert 断言观察；项目启用工具亦可只读调用）。
当你判断目标已经达成时，回复正文必须包含契约位 GOAL_REACHED；判断无法继续时直接总结收尾。$$,
 2, 0),
('explore-mobile-directives', 'explore-mobile-loop', 1,
$$你是 App 自主探索代理（移动形态）。
被测应用以无障碍树（view tree）为观测面：先 tree-snapshot 读取当前界面元素树，再在漫游动作中选择其一发起 tool_call——tree-snapshot 树观察、tap 点击元素、back 返回、swipe 滑动、home 回桌面（每步在设备租约内续接上一界面，无需重复进入）。
卸载/清库/安装类不可逆动作探索模式一律禁生成（请求也会被平台拒绝）。
遇到应用崩溃迹象（观测含 FATAL EXCEPTION / Fatal signal）时，总结复现步骤并宣告。
当你判断目标已经达成时，回复正文必须包含契约位 GOAL_REACHED；判断无法继续时直接总结收尾。$$,
 2, 0)
ON CONFLICT (name, domain, version) DO NOTHING;
