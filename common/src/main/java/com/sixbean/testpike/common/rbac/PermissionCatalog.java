package com.sixbean.testpike.common.rbac;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public enum PermissionCatalog {


    
    CASE_GENERATE("perm:case:case-generate", "单条用例生成", PermissionDomain.CASE, "A1", PermActionFamily.CREATE),
    BATCH_GENERATE("perm:case:batch-generate", "批量用例生成", PermissionDomain.CASE, "A2", PermActionFamily.CREATE),
    COVERAGE_GENERATE("perm:case:coverage-generate", "覆盖补齐生成", PermissionDomain.CASE, "A3", PermActionFamily.CREATE),
    STYLE_ADAPT("perm:case:style-adapt", "风格适配", PermissionDomain.CASE, "A4", PermActionFamily.EDIT),
    COVERAGE_CHECK("perm:case:coverage-check", "验收标准覆盖检查", PermissionDomain.CASE, "A5", PermActionFamily.VIEW),
    SELF_CHECK("perm:case:self-check", "语法与完整性自检", PermissionDomain.CASE, "A6", PermActionFamily.RUN),
    PRIORITY_SUGGEST("perm:case:priority-suggest", "优先级建议", PermissionDomain.CASE, "A7", PermActionFamily.VIEW),
    CASE_CLONE("perm:case:case-clone", "用例克隆", PermissionDomain.CASE, "A8", PermActionFamily.CREATE),
    SIMILARITY_HINT("perm:case:similarity-hint", "相似用例提示", PermissionDomain.CASE, "A9", PermActionFamily.VIEW),

    
    TO_SCRIPT("perm:script:to-script", "用例→脚本转换", PermissionDomain.SCRIPT, "B1", PermActionFamily.CREATE),
    LOCATE_STRATEGY("perm:script:locate-strategy", "元素定位策略生成", PermissionDomain.SCRIPT, "B2", PermActionFamily.RUN),
    TRIAL_RUN("perm:script:trial-run", "试运行验证", PermissionDomain.SCRIPT, "B3", PermActionFamily.RUN),
    AUTO_FIX("perm:script:auto-fix", "失败自动修复（L1 自愈）", PermissionDomain.SCRIPT, "B4", PermActionFamily.RUN),
    SCRIPT_VERSION("perm:script:script-version", "脚本版本关联", PermissionDomain.SCRIPT, "B5", PermActionFamily.VIEW),
    CODEGEN_NORMALIZE("perm:script:codegen-normalize", "codegen 脚本规范化", PermissionDomain.SCRIPT, "B6", PermActionFamily.EDIT),
    VISUAL_ASSERT("perm:script:visual-assert", "视觉回归断言", PermissionDomain.SCRIPT, "B7", PermActionFamily.RUN),
    CODE_STEP("perm:script:code-step", "自定义代码步骤", PermissionDomain.SCRIPT, "B8", PermActionFamily.EDIT),
    ASSERT_ENHANCE("perm:script:assert-enhance", "断言匹配模式增强（expect range/regex）", PermissionDomain.SCRIPT, "B10", PermActionFamily.EDIT),
    NON_UI_ASSERT("perm:script:non-ui-assert", "非 UI 断言原语（console/net/http/sql）", PermissionDomain.SCRIPT, "B11", PermActionFamily.EDIT),
    EXPLORE("perm:script:explore", "自主探索执行（Agentic）", PermissionDomain.SCRIPT, "B9", PermActionFamily.RUN),
    SCRIPT_EDIT("perm:script:script-edit", "脚本手工编辑与拖拽增强（含 loop/if）", PermissionDomain.SCRIPT, "B12", PermActionFamily.EDIT),

    
    FLOW_GENERATE("perm:review:flow-generate", "流程图生成", PermissionDomain.REVIEW, "C1", PermActionFamily.CREATE),
    FLOW_SYNC("perm:review:flow-sync", "描述↔流程图双向同步", PermissionDomain.REVIEW, "C2", PermActionFamily.EDIT),
    FAILURE_CLASSIFY("perm:review:failure-classify", "失败原因智能分类（辅助）", PermissionDomain.REVIEW, "C3", PermActionFamily.VIEW),
    AUTO_ASSIGN("perm:review:auto-assign", "用例自动分配（负载均衡）", PermissionDomain.REVIEW, "C4", PermActionFamily.RUN),

    
    FEEDBACK_SUBMIT("perm:feedback:feedback-submit", "质量反馈接收", PermissionDomain.FEEDBACK, "D1", PermActionFamily.CREATE),
    SAMPLE_COLLECT("perm:feedback:sample-collect", "训练样本收集", PermissionDomain.FEEDBACK, "D2", PermActionFamily.RUN),
    EXCELLENT_MARK("perm:feedback:excellent-mark", "优秀用例标记学习", PermissionDomain.FEEDBACK, "D3", PermActionFamily.CREATE),
    MODEL_UPDATE("perm:feedback:model-update", "模型更新机制", PermissionDomain.FEEDBACK, "D4", PermActionFamily.MANAGE),
    PHRASING_ADOPT("perm:feedback:phrasing-adopt", "措辞偏好采纳", PermissionDomain.FEEDBACK, "D5", PermActionFamily.APPROVE),
    FEEDBACK_REPORT("perm:feedback:feedback-report", "反馈汇总报告", PermissionDomain.FEEDBACK, "D6", PermActionFamily.VIEW),

    
    PROVIDER_CONFIG("perm:platform:provider-config", "LLM Provider 适配层", PermissionDomain.PLATFORM, "E1", PermActionFamily.MANAGE),
    SECRET_MANAGE("perm:platform:secret-manage", "密钥集中管理", PermissionDomain.PLATFORM, "E2", PermActionFamily.MANAGE),
    COST_BUDGET("perm:platform:cost-budget", "Token 成本统计与预算", PermissionDomain.PLATFORM, "E3", PermActionFamily.MANAGE),
    PROMPT_GUARD("perm:platform:prompt-guard", "Prompt 注入防护", PermissionDomain.PLATFORM, "E4", PermActionFamily.RUN),
    DATA_MASK("perm:platform:data-mask", "训练数据脱敏", PermissionDomain.PLATFORM, "E5", PermActionFamily.RUN),
    ASYNC_CONSUME("perm:platform:async-consume", "异步任务消费", PermissionDomain.PLATFORM, "E6", PermActionFamily.RUN),
    TIMEOUT_RETRY("perm:platform:timeout-retry", "超时与重试治理", PermissionDomain.PLATFORM, "E7", PermActionFamily.MANAGE),
    CONTEXT_COMPRESS("perm:platform:context-compress", "上下文动态压缩", PermissionDomain.PLATFORM, "E8", PermActionFamily.RUN),
    DEPLOY_SUPPORT("perm:platform:deploy-support", "分布式部署支持", PermissionDomain.PLATFORM, "E9", PermActionFamily.MANAGE),
    CONCURRENCY_CONTROL("perm:platform:concurrency-control", "并发控制", PermissionDomain.PLATFORM, "E10", PermActionFamily.RUN),
    FEISHU_NOTIFY("perm:platform:feishu-notify", "飞书通知集成", PermissionDomain.PLATFORM, "E11", PermActionFamily.MANAGE),
    I18N("perm:platform:i18n", "国际化（i18n）", PermissionDomain.PLATFORM, "E12", PermActionFamily.MANAGE),

    
    DOC_UPLOAD("perm:document:doc-upload", "文档摄取与解析", PermissionDomain.DOCUMENT, "F1", PermActionFamily.CREATE),
    REQUIREMENT_EXTRACT("perm:document:requirement-extract", "需求点提取", PermissionDomain.DOCUMENT, "F2", PermActionFamily.RUN),
    CHANGE_DETECT("perm:document:change-detect", "文档变更检测与影响分析", PermissionDomain.DOCUMENT, "F3", PermActionFamily.VIEW),
    API_DOC_ANALYZE("perm:document:api-doc-analyze", "接口文档分析", PermissionDomain.DOCUMENT, "F4", PermActionFamily.RUN),
    DOC_CONTEXT_BUDGET("perm:document:doc-context-budget", "上下文预算管理", PermissionDomain.DOCUMENT, "F5", PermActionFamily.MANAGE),

    
    KB_BUILD("perm:knowledge:kb-build", "知识库构建", PermissionDomain.KNOWLEDGE, "G1", PermActionFamily.MANAGE),
    KB_RETRIEVE("perm:knowledge:kb-retrieve", "生成时检索增强", PermissionDomain.KNOWLEDGE, "G2", PermActionFamily.RUN),
    MCP_CLIENT("perm:knowledge:mcp-client", "MCP 客户端接入", PermissionDomain.KNOWLEDGE, "G3", PermActionFamily.MANAGE),
    PAGE_PROBE("perm:knowledge:page-probe", "页面探查（阶段 2）", PermissionDomain.KNOWLEDGE, "G4", PermActionFamily.RUN),
    TOOL_ACL("perm:knowledge:tool-acl", "工具权限管控", PermissionDomain.KNOWLEDGE, "G5", PermActionFamily.MANAGE),
    MCP_SERVER("perm:knowledge:mcp-server", "自身 MCP Server 能力暴露", PermissionDomain.KNOWLEDGE, "G6", PermActionFamily.MANAGE),
    MCP_MANAGE("perm:knowledge:mcp-manage", "MCP 服务管理", PermissionDomain.KNOWLEDGE, "G7", PermActionFamily.MANAGE),

    
    PROJECT_VIEW("perm:project:project-view", "Project 实体与数据模型", PermissionDomain.PROJECT, "H1", PermActionFamily.VIEW),
    PROJECT_PERM("perm:project:project-perm", "项目级权限", PermissionDomain.PROJECT, "H2", PermActionFamily.MANAGE),
    PROJECT_ENV_CONFIG("perm:project:project-env-config", "项目环境配置", PermissionDomain.PROJECT, "H3", PermActionFamily.MANAGE),
    EXECUTOR_POOL("perm:project:executor-pool", "执行器资源池", PermissionDomain.PROJECT, "H4", PermActionFamily.VIEW),
    PROJECT_KB("perm:project:project-kb", "知识库与风格按项目", PermissionDomain.PROJECT, "H5", PermActionFamily.MANAGE),
    PROJECT_REPORT("perm:project:project-report", "项目级报表", PermissionDomain.PROJECT, "H6", PermActionFamily.VIEW),
    PROJECT_INIT("perm:project:project-init", "模板项目初始化", PermissionDomain.PROJECT, "H7", PermActionFamily.CREATE),
    PLUGIN_FRAMEWORK("perm:project:plugin-framework", "行业插件扩展点框架（POS/ERP 样例）", PermissionDomain.PROJECT, "H8", PermActionFamily.MANAGE),

    
    BATCH_VIEW("perm:batch:batch-view", "测试批次（Test Run）", PermissionDomain.BATCH, "I1", PermActionFamily.VIEW),
    SCHEDULE_CONFIG("perm:batch:schedule-config", "定时调度", PermissionDomain.BATCH, "I2", PermActionFamily.MANAGE),
    RESUME_RUN("perm:batch:resume-run", "断点续跑与故障恢复", PermissionDomain.BATCH, "I3", PermActionFamily.RUN),
    TIMEOUT_WATCHDOG("perm:batch:timeout-watchdog", "超时与看门狗", PermissionDomain.BATCH, "I4", PermActionFamily.MANAGE),
    PARALLEL_CONTROL("perm:batch:parallel-control", "并行度控制", PermissionDomain.BATCH, "I5", PermActionFamily.MANAGE),
    BATCH_CONSOLE("perm:batch:batch-console", "批次控制台", PermissionDomain.BATCH, "I6", PermActionFamily.VIEW),
    BATCH_REPORT("perm:batch:batch-report", "批次级报告与通知", PermissionDomain.BATCH, "I7", PermActionFamily.VIEW),
    CICD_INTEGRATE("perm:batch:cicd-integrate", "CI/CD 集成", PermissionDomain.BATCH, "I8", PermActionFamily.MANAGE),
    QUICK_RUN("perm:batch:quick-run", "快速执行（单条/临时）", PermissionDomain.BATCH, "I9", PermActionFamily.RUN),
    LEGACY_IMPORT("perm:batch:legacy-import", "存量导入（上线迁移）", PermissionDomain.BATCH, "J1", PermActionFamily.CREATE),
    DEFECT_LINK("perm:batch:defect-link", "缺陷关联与闭环", PermissionDomain.BATCH, "J2", PermActionFamily.EDIT),
    REPORT_EXPORT("perm:batch:report-export", "报告统计与导出", PermissionDomain.BATCH, "J3", PermActionFamily.EXPORT),
    COVERAGE_MATRIX("perm:batch:coverage-matrix", "覆盖矩阵视图", PermissionDomain.BATCH, "J4", PermActionFamily.VIEW),
    RUN_HISTORY("perm:batch:run-history", "用例执行历史", PermissionDomain.BATCH, "J5", PermActionFamily.VIEW),
    ENV_SELECT("perm:batch:env-select", "执行环境选择", PermissionDomain.BATCH, "J6", PermActionFamily.RUN),

    
    OPEN_SOURCE_BUILD("perm:system:open-source-build", "开源化重写", PermissionDomain.SYSTEM, "K1", PermActionFamily.MANAGE),
    EXECUTOR_LOG("perm:system:executor-log", "执行器日志查看", PermissionDomain.SYSTEM, "K2", PermActionFamily.VIEW),
    USER_MANAGE("perm:system:user-manage", "用户管理增强（百人团队）", PermissionDomain.SYSTEM, "K3", PermActionFamily.MANAGE),
    ROLE_MANAGE("perm:system:role-manage", "RBAC 角色权限", PermissionDomain.SYSTEM, "K4", PermActionFamily.MANAGE),
    USER_PROFILE("perm:system:user-profile", "用户 Profile", PermissionDomain.SYSTEM, "K5", PermActionFamily.EDIT),
    ASSIGN_CHECK("perm:system:assign-check", "分配人跨项目校验", PermissionDomain.SYSTEM, "K6", PermActionFamily.RUN),
    REQUIREMENT_HELP("perm:system:requirement-help", "需求点创建帮助", PermissionDomain.SYSTEM, "K7", PermActionFamily.VIEW),
    SYSTEM_CONFIG("perm:system:system-config", "系统配置", PermissionDomain.SYSTEM, "K8", PermActionFamily.MANAGE),
    LLM_CONFIG("perm:system:llm-config", "项目级大模型配置", PermissionDomain.SYSTEM, "K9", PermActionFamily.MANAGE),
    CASE_PHASE("perm:system:case-phase", "用例归属阶段", PermissionDomain.SYSTEM, "K10", PermActionFamily.EDIT),
    STORAGE_CONFIG("perm:system:storage-config", "存储后端抽象与云存储", PermissionDomain.SYSTEM, "K11", PermActionFamily.MANAGE),
    PERMISSION_CATALOG("perm:system:permission-catalog", "权限细化（功能点级）", PermissionDomain.SYSTEM, "K12", PermActionFamily.MANAGE),
    PERF_TUNING("perm:system:perf-tuning", "性能优化专项", PermissionDomain.SYSTEM, "K13", PermActionFamily.MANAGE),

    
    PLAN_MANAGE("perm:testops:plan-manage", "测试执行计划", PermissionDomain.TESTOPS, "L1", PermActionFamily.CREATE),
    DEFECT_LIB("perm:testops:defect-lib", "内置缺陷库", PermissionDomain.TESTOPS, "L2", PermActionFamily.VIEW),
    REGRESSION_SET("perm:testops:regression-set", "回归集", PermissionDomain.TESTOPS, "L3", PermActionFamily.CREATE),
    SUMMARY_REPORT("perm:testops:summary-report", "测试总结报告", PermissionDomain.TESTOPS, "L4", PermActionFamily.EXPORT),
    DATA_FACTORY("perm:testops:data-factory", "自动造数（数据工厂）", PermissionDomain.TESTOPS, "L5", PermActionFamily.RUN),
    ENV_STATUS("perm:testops:env-status", "环境部署状态", PermissionDomain.TESTOPS, "L6", PermActionFamily.VIEW),
    PROGRESS_BOARD("perm:testops:progress-board", "执行进度看板", PermissionDomain.TESTOPS, "L7", PermActionFamily.VIEW),

    
    AUDIT_VIEW("perm:detail:audit-view", "编辑审计全覆盖 + 审计查看", PermissionDomain.DETAIL, "M1", PermActionFamily.MANAGE),
    PLAN_LOCK("perm:detail:plan-lock", "计划锁定与严格执行模式", PermissionDomain.DETAIL, "M2", PermActionFamily.MANAGE),
    CASE_TAG("perm:detail:case-tag", "用例标签", PermissionDomain.DETAIL, "M3", PermActionFamily.EDIT),
    DELETE_PROTECT("perm:detail:delete-protect", "删除保护与引用完整性", PermissionDomain.DETAIL, "M4", PermActionFamily.RUN),

    
    LOGIN_LOCK("perm:security:login-lock", "登录失败锁定与解锁", PermissionDomain.SECURITY, "N1", PermActionFamily.MANAGE),
    DATA_POOL("perm:security:data-pool", "数据池占用/释放生命周期", PermissionDomain.SECURITY, "N2", PermActionFamily.RUN),
    EXECUTOR_ALERT("perm:security:executor-alert", "执行器离线告警", PermissionDomain.SECURITY, "N3", PermActionFamily.VIEW),
    CASE_BATCH_EDIT("perm:security:case-batch-edit", "用例批量编辑", PermissionDomain.SECURITY, "N4", PermActionFamily.EDIT),
    NOTIFY_CHANNEL_CONFIG("perm:security:notify-channel-config", "通知渠道配置与 webhook", PermissionDomain.SECURITY, "N5", PermActionFamily.MANAGE),
    START_PRECHECK("perm:security:start-precheck", "批次/计划启动用例状态校验", PermissionDomain.SECURITY, "N6", PermActionFamily.RUN),
    CASE_FORM("perm:security:case-form", "用例新建/编辑表单整合（标签+分配人）", PermissionDomain.SECURITY, "N7", PermActionFamily.EDIT),
    SELECTOR_ENHANCE("perm:security:selector-enhance", "选择器增强（项目/用例搜索）", PermissionDomain.SECURITY, "N8", PermActionFamily.VIEW),
    NAV_RETAIN("perm:security:nav-retain", "返回导航与数据保留", PermissionDomain.SECURITY, "N9", PermActionFamily.VIEW),

    
    GLOBAL_SEARCH("perm:ux:global-search", "全局搜索", PermissionDomain.UX, "O1", PermActionFamily.VIEW),
    BREADCRUMB("perm:ux:breadcrumb", "面包屑导航", PermissionDomain.UX, "O2", PermActionFamily.VIEW),
    COLUMN_CONFIG("perm:ux:column-config", "表格列配置", PermissionDomain.UX, "O3", PermActionFamily.EDIT),
    VIRTUAL_SCROLL("perm:ux:virtual-scroll", "表格虚拟滚动", PermissionDomain.UX, "O4", PermActionFamily.VIEW),
    FORM_ENHANCE("perm:ux:form-enhance", "表单交互增强", PermissionDomain.UX, "O5", PermActionFamily.VIEW),
    NOTIFY_CENTER("perm:ux:notify-center", "通知中心独立页", PermissionDomain.UX, "O6", PermActionFamily.VIEW),
    HOME_DASHBOARD("perm:ux:home-dashboard", "首页工作台（待办聚合）", PermissionDomain.UX, "O7", PermActionFamily.VIEW),
    REVIEW_EFFICIENCY("perm:ux:review-efficiency", "评审效率增强", PermissionDomain.UX, "O8", PermActionFamily.VIEW),
    RUN_RESULT_EXPERIENCE("perm:ux:run-result-experience", "执行结果体验", PermissionDomain.UX, "O9", PermActionFamily.VIEW),
    REPORT_CENTER("perm:ux:report-center", "高级报表与统计口径固化", PermissionDomain.UX, "O10", PermActionFamily.MANAGE),
    TRASH_RESTORE("perm:ux:trash-restore", "回收站与误删恢复", PermissionDomain.UX, "P1", PermActionFamily.RUN),
    CASE_EXPORT("perm:ux:case-export", "用例/缺陷批量导出", PermissionDomain.UX, "P2", PermActionFamily.EXPORT),
    TRACE_VIEW("perm:ux:trace-view", "追溯链视图", PermissionDomain.UX, "P3", PermActionFamily.VIEW),
    FAVORITE("perm:ux:favorite", "收藏与最近访问", PermissionDomain.UX, "P4", PermActionFamily.VIEW),
    NOTIFY_PREFERENCE("perm:ux:notify-preference", "通知订阅偏好", PermissionDomain.UX, "P5", PermActionFamily.EDIT),
    CASE_DRAFT("perm:ux:case-draft", "用例编辑器自动存草稿", PermissionDomain.UX, "P6", PermActionFamily.EDIT),

    
    VERSION_HISTORY("perm:scenario:version-history", "用例版本历史 diff 与回滚", PermissionDomain.SCENARIO, "Q1", PermActionFamily.VIEW),
    MATRIX_CELL("perm:scenario:matrix-cell", "覆盖矩阵单元格直达", PermissionDomain.SCENARIO, "Q2", PermActionFamily.VIEW),
    FLAKY_ISOLATE("perm:scenario:flaky-isolate", "Flaky 用例检测与隔离", PermissionDomain.SCENARIO, "Q3", PermActionFamily.RUN),
    REVIEW_REMIND("perm:scenario:review-remind", "评审待办催办与超时", PermissionDomain.SCENARIO, "Q4", PermActionFamily.RUN),
    DAILY_DIGEST("perm:scenario:daily-digest", "每日测试日报摘要", PermissionDomain.SCENARIO, "Q5", PermActionFamily.VIEW),
    RETENTION_POLICY("perm:scenario:retention-policy", "数据保留策略配置化", PermissionDomain.SCENARIO, "Q6", PermActionFamily.MANAGE),

    
    ENV_HEALTH("perm:safetyops:env-health", "环境健康检查落地", PermissionDomain.SAFETYOPS, "R1", PermActionFamily.RUN),
    SESSION_REVOKE("perm:safetyops:session-revoke", "登出即吊销会话", PermissionDomain.SAFETYOPS, "R2", PermActionFamily.RUN),
    AUDIT_CHAIN_VERIFY("perm:safetyops:audit-chain-verify", "审计链完整性校验入口", PermissionDomain.SAFETYOPS, "R3", PermActionFamily.RUN),
    LOGIN_LOCKED_EVENT("perm:safetyops:login-locked-event", "LOGIN_LOCKED 事件补生", PermissionDomain.SAFETYOPS, "R4", PermActionFamily.RUN),
    PASSWORD_POLICY("perm:safetyops:password-policy", "密码有效期策略", PermissionDomain.SAFETYOPS, "R5", PermActionFamily.MANAGE),
    SESSION_REFRESH("perm:safetyops:session-refresh", "会话刷新机制（双 token 轮换）", PermissionDomain.SAFETYOPS, "R6", PermActionFamily.RUN),

    
    MANAGE("perm:skill:manage", "SKILL 定义与版本管理", PermissionDomain.SKILL, "S1", PermActionFamily.CREATE),
    APPROVE("perm:skill:approve", "SKILL 审批流", PermissionDomain.SKILL, "S2", PermActionFamily.APPROVE),
    MARKET("perm:skill:market", "SKILL 市场", PermissionDomain.SKILL, "S3", PermActionFamily.VIEW),
    CONFIG("perm:skill:config", "项目 SKILL 配置", PermissionDomain.SKILL, "S4", PermActionFamily.EDIT),
    CONSUME("perm:skill:consume", "SKILL AI 消费链", PermissionDomain.SKILL, "S5", PermActionFamily.RUN),

    
    TOOL_MARKET("perm:tool:tool-market", "tools 注册与市场", PermissionDomain.TOOL, "U1", PermActionFamily.MANAGE),
    TOOL_DOC_MANAGE("perm:tool:tool-doc-manage", "文档类工具纳管", PermissionDomain.TOOL, "U2", PermActionFamily.MANAGE),
    TOOL_SPI("perm:tool:tool-spi", "可执行工具通用接口（ToolSPI）", PermissionDomain.TOOL, "U3", PermActionFamily.MANAGE),
    TOOL_CONSUME("perm:tool:tool-consume", "AI 工具消费链", PermissionDomain.TOOL, "U4", PermActionFamily.RUN),

 
    TASK_CANCEL("perm:exec:task-cancel", "任务终止与进程管理", PermissionDomain.EXEC, "V11", PermActionFamily.RUN),

 
    COMMENT_USE("perm:comment:use", "评论与附件（四宿主+提及）", PermissionDomain.COMMENT, "V14", PermActionFamily.CREATE),

 
    APPAUTO_DEVICE_VIEW("perm:appauto:device:view", "APP 设备台账查看", PermissionDomain.APPAUTO, "W5a", PermActionFamily.VIEW),
    APPAUTO_DEVICE_MANAGE("perm:appauto:device:manage", "APP 设备台账管理", PermissionDomain.APPAUTO, "W5b", PermActionFamily.MANAGE),
    APPAUTO_ENGINE_VIEW("perm:appauto:engine:view", "引擎目录查看", PermissionDomain.APPAUTO, "W6a", PermActionFamily.VIEW),
    APPAUTO_ENGINE_MANAGE("perm:appauto:engine:manage", "引擎目录管理", PermissionDomain.APPAUTO, "W6b", PermActionFamily.MANAGE),
    APPAUTO_PLATFORM_VIEW("perm:appauto:platform:view", "平台目录查看", PermissionDomain.APPAUTO, "W6c", PermActionFamily.VIEW),
    APPAUTO_PLATFORM_MANAGE("perm:appauto:platform:manage", "平台目录管理", PermissionDomain.APPAUTO, "W6d", PermActionFamily.MANAGE),
    APPAUTO_PROFILE_VIEW("perm:appauto:profile:view", "环境档案查看", PermissionDomain.APPAUTO, "W6e", PermActionFamily.VIEW),
    APPAUTO_PROFILE_MANAGE("perm:appauto:profile:manage", "环境档案管理", PermissionDomain.APPAUTO, "W6f", PermActionFamily.MANAGE),
    APPAUTO_RECORD("perm:appauto:record", "录制转流程发起（草稿反推）", PermissionDomain.APPAUTO, "W6g", PermActionFamily.RUN),
    APPAUTO_WXMOCK_RUN("perm:appauto:wxmock:run", "小程序 mock 数据集智辅生成", PermissionDomain.APPAUTO, "W6h", PermActionFamily.RUN),
    APPAUTO_ADVISOR_RUN("perm:appauto:advisor:run", "升级顾问风险清单征询（AI 消耗闸）", PermissionDomain.APPAUTO, "W6i", PermActionFamily.RUN),
    APPAUTO_MATRIX_RUN("perm:appauto:matrix:run", "机型矩阵扇出派发（烧机时消耗闸）", PermissionDomain.APPAUTO, "W6j", PermActionFamily.RUN),
    APPAUTO_CRED_VIEW("perm:appauto:cred:view", "凭据保险箱查看（打码面）", PermissionDomain.APPAUTO, "W6k", PermActionFamily.VIEW),
    APPAUTO_CRED_MANAGE("perm:appauto:cred:manage", "凭据保险箱管理（建档/改密/禁用）", PermissionDomain.APPAUTO, "W6l", PermActionFamily.MANAGE),
    APPAUTO_PKG_VIEW("perm:appauto:pkg:view", "被测应用包档案查看", PermissionDomain.APPAUTO, "W6m", PermActionFamily.VIEW),
    APPAUTO_PKG_MANAGE("perm:appauto:pkg:manage", "被测应用包档案管理", PermissionDomain.APPAUTO, "W6n", PermActionFamily.MANAGE),
    APPAUTO_POPUP_RULE_RUN("perm:appauto:popup-rule:run", "弹窗规则自学发起（AI 消耗闸）", PermissionDomain.APPAUTO, "W6o", PermActionFamily.RUN),
    APPAUTO_POPUP_RULE_APPROVE("perm:appauto:popup-rule:approve", "弹窗规则草稿人批准入库", PermissionDomain.APPAUTO, "W6p", PermActionFamily.APPROVE),

 
    APPAUTO_PERF_VIEW("perm:appauto:perf:view", "移动端性能采样曲线查看", PermissionDomain.APPAUTO, "W6q", PermActionFamily.VIEW),

 
    APPAUTO_EXPLORE_RUN("perm:appauto:explore:run", "App 探索测试发起（移动会话+设备占用消耗闸）", PermissionDomain.APPAUTO, "W6r", PermActionFamily.RUN),

 
    APPAUTO_WATCH_TAKEOVER("perm:appauto:watch:takeover", "设备接管操作发起", PermissionDomain.APPAUTO, "W6s", PermActionFamily.RUN),

 
    APPAUTO_RESERVATION_VIEW("perm:appauto:reservation:view", "设备预约与日历查看", PermissionDomain.APPAUTO, "W6t", PermActionFamily.VIEW),
    APPAUTO_RESERVATION_CREATE("perm:appauto:reservation:create", "设备预约创建与归还取消", PermissionDomain.APPAUTO, "W6u", PermActionFamily.CREATE),
    APPAUTO_RESERVATION_APPROVE("perm:appauto:reservation:approve", "设备预约审批（批准/驳回）", PermissionDomain.APPAUTO, "W6v", PermActionFamily.APPROVE),

 
    CLUSTER_INSTALL_VIEW("perm:cluster:install:view", "装机入池令牌查看", PermissionDomain.CLUSTER, "W7a", PermActionFamily.VIEW),
    CLUSTER_INSTALL_RUN("perm:cluster:install:run", "装机入池令牌签发与撤销", PermissionDomain.CLUSTER, "W7b", PermActionFamily.RUN),
    CLUSTER_NODE_SCALE("perm:cluster:node:scale", "节点扩容入池", PermissionDomain.CLUSTER, "W7c", PermActionFamily.RUN),
    CLUSTER_NODE_DRAIN("perm:cluster:node:drain", "节点缩容排空", PermissionDomain.CLUSTER, "W7d", PermActionFamily.RUN),
    CLUSTER_UPGRADE_VIEW("perm:cluster:upgrade:view", "版本台账与升级查看", PermissionDomain.CLUSTER, "W7e", PermActionFamily.VIEW),
    CLUSTER_UPGRADE_RUN("perm:cluster:upgrade:run", "节点升级执行", PermissionDomain.CLUSTER, "W7f", PermActionFamily.RUN),
    CLUSTER_MANIFEST_VIEW("perm:cluster:manifest:view", "发布清单查看", PermissionDomain.CLUSTER, "W7g", PermActionFamily.VIEW),
    CLUSTER_RELEASE_CANDIDATE_VIEW("perm:cluster:release:candidate:view", "版本候选待确认卡与角标查看", PermissionDomain.CLUSTER, "AF-T350v", PermActionFamily.VIEW),
    CLUSTER_RELEASE_CANDIDATE_CONFIRM("perm:cluster:release:candidate:confirm", "版本候选确认发布（发布保持人工权威）", PermissionDomain.CLUSTER, "AF-T350c", PermActionFamily.RUN),

 /** One-click platform upgrade / rollback initiation (seeded by , consumed
 * by ; design 8A item seven — swapping packages and restarting services is
 * executed by the host service-agent, rollback targets the ledger's
 * last-known-good. Two separate keys so an enterprise gate can tighten the
 * authorization surface per window). */
    CLUSTER_PLATFORM_UPGRADE("perm:cluster:platform:upgrade", "平台服务一键升级发起", PermissionDomain.CLUSTER, "AF-T354u", PermActionFamily.RUN),
    CLUSTER_PLATFORM_ROLLBACK("perm:cluster:platform:rollback", "平台服务一键回滚发起", PermissionDomain.CLUSTER, "AF-T354r", PermActionFamily.RUN),

 /** API mock management surface (AG ruling: four keys, one per capability so a
 * publish gate can be tightened independently of the write gate). Write
 * starts at case-edit parity (developer and up); publish = approval family
 * (project manager and up, per ruling "publish/retire belongs to PM");
 * traffic = quota/audit ownership on the external serving plane. RTM codes
 * are AG placeholders formalized by the closing pack. */
    MOCK_READ("perm:mock:read", "接口仿真空间与规则查看", PermissionDomain.MOCK, "AG-T357r", PermActionFamily.VIEW),
    MOCK_WRITE("perm:mock:write", "接口仿真空间与规则编辑", PermissionDomain.MOCK, "AG-T357w", PermActionFamily.EDIT),
    MOCK_PUBLISH("perm:mock:publish", "接口仿真规则发布与退役", PermissionDomain.MOCK, "AG-T357p", PermActionFamily.APPROVE),
    MOCK_TRAFFIC("perm:mock:traffic", "接口仿真流量配额与审计归属", PermissionDomain.MOCK, "AG-T357t", PermActionFamily.RUN),

 /** API test asset surface (AH batch W-c2 contract inventory page). The key
 * is three-segment (domain:resource:action) following the frozen appauto
 * family precedent, and deliberately NOT the design-doc literal
 * "perm:api-inventory:read": the catalog key-format gate rejects a hyphen
 * in the domain segment (same correction history as perm:comment →
 * perm:comment:use). VIEW family = every member role carries it; the H1
 * project guard remains the isolation single-source, this key only opens
 * the page door. RTM anchor formalized by the closing pack. */
    APITEST_INVENTORY_READ("perm:apitest:inventory:read", "接口清单查看", PermissionDomain.APITEST, "AH-T370", PermActionFamily.VIEW),

 /** Online dialog door . VIEW family = every member role
 * carries it: the chat dock is open to all project members while the H1
 * project guard in core stays the isolation single-source (this key only
 * opens the page/stream door, it never widens data access). RTM anchor
 * formalized by the closing pack. */
    AICHAT_USE("perm:aichat:use", "在线 AI 对话", PermissionDomain.AICHAT, "AO-T426", PermActionFamily.VIEW),

 /** Project-memory ledger door . VIEW family by
 * design: the ledger tab is readable by every project member, so the key
 * hit-set equals the current login-only allow set (zero drift mount, same
 * posture as S3). Governance writes stay behind the project-lead role
 * guard single-source (SkillAccessGuard.assertCanManage reuse) — this key
 * only opens the read door and gates the tab's visibility. */
    AICHAT_PROJECT_MEMORY("perm:aichat:project-memory", "项目记忆账本查看", PermissionDomain.AICHAT, "AP-T449", PermActionFamily.VIEW),

 /** Shared asset pool doors (Y wave publish-reference system). Pool read
 * is VIEW family on purpose: global discoverability of published heads is
 * the product rule of the asset pool, the VIEW key only opens the page
 * door and every write fence stays core single-source (owner project
 * check + lead/member guards), so widening the read door cannot widen
 * data modification. publish = CREATE family (leads), withdraw = APPROVE
 * family (leads), pin = EDIT family (case-editor content hosts). */
    ASSET_POOL_READ("perm:asset:pool:read", "共享资产池查看", PermissionDomain.ASSET, "Y-T288r", PermActionFamily.VIEW),
    ASSET_PUBLISH("perm:asset:publish", "共享资产发布与改版", PermissionDomain.ASSET, "Y-T288p", PermActionFamily.CREATE),
    ASSET_WITHDRAW("perm:asset:withdraw", "共享资产下架", PermissionDomain.ASSET, "Y-T288w", PermActionFamily.APPROVE),
    ASSET_PIN("perm:asset:pin", "资产引用钉定登记", PermissionDomain.ASSET, "Y-T288i", PermActionFamily.EDIT),

 /** Dead-letter ledger ops door . VIEW family on purpose: the
 * triage page is read-only; replay/void write doors land with behind
 * their own keys. */
    DEAD_LETTER_READ("perm:system:dead-letter-read", "死信台账查看", PermissionDomain.SYSTEM, "AJ-T383", PermActionFamily.VIEW),

 /** Dead-letter write doors . Replay = RUN family (re-posts a
 * booked message, the guard single-source is core DeadLetterReplayService);
 * void = APPROVE family (terminal disposition with a recorded reason). Both
 * keys are catalog-only until granted; admin role gets the full catalog
 * expansion, so no seed rows are needed for the system administrator. */
    DEAD_LETTER_REPLAY("perm:system:dead-letter-replay", "死信台账重放", PermissionDomain.SYSTEM, "AJ-T384r", PermActionFamily.RUN),
    DEAD_LETTER_VOID("perm:system:dead-letter-void", "死信台账作废", PermissionDomain.SYSTEM, "AJ-T384v", PermActionFamily.APPROVE),

 /** Evaluation-set read door (AW1). VIEW family: the page is visible from
  * developer up (R24-5 no-narrowing ruling for the new leaf); writes ride the
  * separate manage key below. */
    JUDGE_EVALSET_VIEW("perm:judge:evalset:view", "评测集与样本清单查看", PermissionDomain.JUDGE, "AW1", PermActionFamily.VIEW),

 /** Evaluation-set authoring (AW2): manual raise, batch/adopted promotion,
  * publish-as-new-version, disable. CREATE family per the skill/asset
  * authoring precedent — the D2 exit gate addresses testers, so the write
  * door must sit below the admin-only MANAGE family. */
    JUDGE_EVALSET_MANAGE("perm:judge:evalset:manage", "评测集样本维护", PermissionDomain.JUDGE, "AW2", PermActionFamily.CREATE),

 /** AI question generation wizard (AW3): qa-preview + cost card, then
  * publish the checked subset. RUN family = AI-cost-gate lane precedent
  * (appauto advisor/matrix/popup-rule all gate model spend with RUN). */
    JUDGE_QAGEN_RUN("perm:judge:qagen:run", "AI 造题预览发起", PermissionDomain.JUDGE, "AW3", PermActionFamily.RUN),

 /** Judge-run dispatch (AW4): start one evaluation run from set version x
  * rubric x target. CREATE family per the AW4 ruling. */
    JUDGE_RUN_CREATE("perm:judge:run:create", "发起评测运行", PermissionDomain.JUDGE, "AW4", PermActionFamily.CREATE),

 /** Judge-run report read door (M1 migration target). MANAGE family on
  * purpose: the report page rode the borrowed admin-only knowledge G7 key
  * before, and the ruling pins zero crowd drift across built-in roles
  * (audit-view precedent) — the rename fixes name/reality, it does not
  * widen the door. */
    JUDGE_RUN_VIEW("perm:judge:run:view", "评测运行报告查看", PermissionDomain.JUDGE, "AW-M1", PermActionFamily.MANAGE),

 /** Login-state capture wizard (AV1): start a CDP capture session against the
  * operator's own browser and commit checked entries to the ledger. EDIT
  * family so the door sits from-developer-up as the AV design doc freezes
  * (CREATE would drop developers); capture is a ledger write, not a view. */
    SESSION_CAPTURE("perm:session:capture", "登录态采集发起", PermissionDomain.SESSION, "AV1", PermActionFamily.EDIT),

 /** Login-state ledger list/detail read door (AV2 read half). VIEW family:
  * from-developer-up; values are always masked — the ledger never renders
  * secrets (credential red line). */
    SESSION_READ("perm:session:read", "登录态账本查看", PermissionDomain.SESSION, "AV2k", PermActionFamily.VIEW),

 /** Login-state ledger lifecycle (AV2 manage half): disable/renew/delete.
  * APPROVE family = test_lead and up (PM included): the MANAGE family would
  * pin this door to admin only, narrower than the AV design doc freezes for
  * test_lead-and-up governance actions. */
    SESSION_MANAGE("perm:session:manage", "登录态停用/续期/删除", PermissionDomain.SESSION, "AV2l", PermActionFamily.APPROVE),

 /** Reusability verification door (AV3): run a replay/validity check against
  * a ledger row. VIEW family per the from-developer-up freeze; the check
  * itself is server-side and writes only verification timestamps. */
    SESSION_VERIFY("perm:session:verify", "登录态重放核验", PermissionDomain.SESSION, "AV3", PermActionFamily.VIEW),

 /** Consumer-side selection door (AV4): reference a login state from API-set
  * default auth, project variables or UI-case context injection. VIEW family
  * (from-developer-up); actual execution stays gated by the existing apitest
  * run keys — this door only exposes ledger rows to pickers. */
    SESSION_CONSUME("perm:session:consume", "登录态消费引用", PermissionDomain.SESSION, "AV4", PermActionFamily.VIEW),

 /** Captcha-exemption declaration (AV5): register/refresh the target-environment
  * exemption statement (no-captcha/universal code/whitelist account). APPROVE
  * family = test_lead and up, matching the other governance action and the
  * dead-letter-void APPROVE precedent; the platform itself never bypasses a
  * captcha — this door only records what the target system declares. */
    SESSION_EXEMPT("perm:session:exempt", "验证码豁免声明登记", PermissionDomain.SESSION, "AV5", PermActionFamily.APPROVE);

    private final String key;
    private final String label;
    private final PermissionDomain domain;
    private final String functionId;
    private final PermActionFamily family;

    PermissionCatalog(String key, String label, PermissionDomain domain, String functionId,
                      PermActionFamily family) {
        this.key = key;
        this.label = label;
        this.domain = domain;
        this.functionId = functionId;
        this.family = family;
    }

    
    public String key() {
        return key;
    }

    
    public String label() {
        return label;
    }

    
    public PermissionDomain domain() {
        return domain;
    }

    
    public String functionId() {
        return functionId;
    }

    
    public PermActionFamily family() {
        return family;
    }

    
    public static PermissionCatalog fromKey(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

 
    public static boolean exists(String key) {
        return key != null && BY_KEY.containsKey(key);
    }

    
    public static List<String> allKeys() {
        return ALL_KEYS;
    }

    
    public static Map<PermissionDomain, List<PermissionCatalog>> groupedByDomain() {
        return GROUPED;
    }

    private static final Map<String, PermissionCatalog> BY_KEY;
    private static final List<String> ALL_KEYS;
    private static final Map<PermissionDomain, List<PermissionCatalog>> GROUPED;

    static {
        Map<String, PermissionCatalog> byKey = new LinkedHashMap<>();
        Map<PermissionDomain, List<PermissionCatalog>> grouped = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        for (PermissionDomain d : PermissionDomain.values()) {
            grouped.put(d, new ArrayList<>());
        }
        for (PermissionCatalog p : values()) {
            byKey.put(p.key, p);
            keys.add(p.key);
            grouped.get(p.domain).add(p);
        }
        BY_KEY = Collections.unmodifiableMap(byKey);
        ALL_KEYS = Collections.unmodifiableList(keys);
        Map<PermissionDomain, List<PermissionCatalog>> frozen = new LinkedHashMap<>();
        grouped.forEach((d, list) -> frozen.put(d, Collections.unmodifiableList(list)));
        GROUPED = Collections.unmodifiableMap(frozen);
    }
}
