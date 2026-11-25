package com.sixbean.testpike.admin.rbac.app.component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;


public enum PublicEndpointRegistry {

 
    AUTH_LOGIN("POST", "/api/v1/auth/login", "登录前本地账号密码换取 JWT"),
 
    AUTH_SSO_AUTHORIZE_URL("GET", "/api/v1/auth/sso/authorize-url", "登录前获取 OAuth2 授权跳转地址"),
 
    AUTH_SSO_LOGIN("POST", "/api/v1/auth/sso/login", "登录前授权码换取会话 token"),
 
    AUTH_LOGOUT("POST", "/api/v1/auth/logout", "本人会话登出吊销"),
 
    AUTH_REFRESH("POST", "/api/v1/auth/refresh", "本人会话无感续期"),
 
    USERS_ME_GET("GET", "/api/v1/users/me", "本人信息（登录即可）"),
 
    USERS_ME_PUT("PUT", "/api/v1/users/me", "本人资料修改（K5，仅作用于自己）"),
 
    USERS_ME_PASSWORD("PUT", "/api/v1/users/me/password", "本人改密（K5，仅作用于自己）"),
 
    USERS_ME_PERMS("GET", "/api/v1/users/me/perms", "本人功能权限点读取（前端显隐数据源）"),
 
    ME_PERMISSIONS("GET", "/api/v1/me/permissions", "本人 K12 权限码集读取（前端按钮显隐数据源）"),
 
    REPORTS_CALIBERS("GET", "/api/v1/reports/calibers", "口径清单只读（hover 数据源，零用户数据）"),
 
    STORAGE_FILES_GET("GET", "/storage/files/**", "带签预签名下载（持有者=凭证，HMAC 三要素在 core 校验）"),
 
    STORAGE_FILES_PUT("PUT", "/storage/files/**", "带签预签名直传（持有者=凭证）"),
 
    CI_BADGE_SVG("GET", "/open/badge/{badgeToken}.svg", "CI 状态徽章公开只读（T211 api 承载；无认证只读+不可枚举 UUID token=V59 badge_token+缓存 60s）"),
 /** Public dialog-snapshot share door ( P2: served by the api service at
 * /open/chat-share/{shareToken}; same posture as the badge entry — this registry
 * only feeds the contract-consistency gate, the api service has no Spring
 * Security so the face is naturally unauthenticated). Safety = the frozen copy
 * is masked at creation time, the token is a non-enumerable UUID (V332), expiry
 * self-destructs and the owner can revoke; read-only for link holders. */
    CHAT_SHARE_VIEW("GET", "/open/chat-share/{shareToken}", "对话快照只读分享公开端点（T438 P2 api 承载；冻结副本零登录只读，token=不可枚举 UUID，撤回/过期即时失效 no-store）"),
 
    FEISHU_CALLBACK("POST", "/open/feishu/callback", "飞书交互卡片按钮回传（T252 api 承载；X-Lark 验签+防重放+幂等 fail-closed，公开面=带签凭证持有者）"),
 
    HELP_DOCS_LIST("GET", "/api/v1/help/docs", "章节目录只读（V12/T241，登录即可）"),
    HELP_DOCS_DETAIL("GET", "/api/v1/help/docs/{domainCode}", "域章节详情只读（V12/T241，域码=字母码单段通配）"),

 
    CASES_LIST("GET", "/api/v1/cases", "用例列表（K6 项目成员/H1 隔离闸）"),
    
    CASES_GET("GET", "/api/v1/cases/{id}", "用例详情（K6 项目成员/H1 隔离闸）"),
 
    CASES_REFERENCES("GET", "/api/v1/cases/{id}/references", "被计划引用提示（K6 项目成员闸）"),
    /** Pending flaky restore proposals : project-member
 * visibility enforced by ProjectAccessGuard in the controller, same posture
 * as the case list/detail pair; no functional permission point applies. */
    CASES_FLAKY_PROPOSALS("GET", "/api/v1/cases/flaky-proposals", "Flaky 回探待确认清单（K6 项目成员闸）"),
 
    CASES_CREATE("POST", "/api/v1/cases", "手工创建用例（K6 成员且非开发闸）"),
    
    CASES_UPDATE("PUT", "/api/v1/cases/{id}", "手工编辑用例（K6 成员且非开发闸）"),
    
    CASES_DELETE("DELETE", "/api/v1/cases/{id}", "软删用例（K6 项目负责人/系统管理员角色闸）"),
 
    CASES_REVIEW("POST", "/api/v1/cases/{id}/review", "用例评审动作（K6 负责人/被分配人闸）"),
    
    CASES_BATCH_REVIEW("POST", "/api/v1/cases/batch-review", "批量评审（K6 负责人/管理员闸）"),
    
    CASES_REVIEW_LOCK_ACQUIRE("POST", "/api/v1/cases/{id}/review-lock", "评审取锁（同评审动作资格）"),
    
    CASES_REVIEW_LOCK_HEARTBEAT("PUT", "/api/v1/cases/{id}/review-lock/heartbeat", "评审锁心跳（持锁人）"),
    
    CASES_REVIEW_LOCK_RELEASE("DELETE", "/api/v1/cases/{id}/review-lock", "释放评审锁（幂等）"),
 
    CASES_ASSIGN("POST", "/api/v1/cases/{id}/assign", "单条分配（K6 负责人/管理员闸）"),
    
    CASES_BATCH_ASSIGN("POST", "/api/v1/cases/batch-assign", "批量分配（K6 负责人/管理员闸）"),
 
    CASES_SCRIPT_GET("GET", "/api/v1/cases/{id}/script", "脚本查看（K6 项目成员闸）"),
 
    CASES_FLOWCHART_GET("GET", "/api/v1/cases/{id}/flowchart", "流程图查看（K6 项目成员闸）"),
 
    CASES_FLOWCHART_PREVIEW("POST", "/api/v1/cases/{id}/flowchart/preview", "流程图草稿预览（K6 项目成员闸）"),
 
    CASES_RERUN_HINT("POST", "/api/v1/cases/{id}/rerun-hint", "重跑提示查询（K6 成员且非开发闸）"),
 
    PLANS_LIST("GET", "/api/v1/plans", "计划列表（K6 项目成员/H1 隔离闸）"),
    
    PLANS_CREATE("POST", "/api/v1/plans", "创建计划（K6 项目成员闸）"),
    
    PLANS_GET("GET", "/api/v1/plans/{id}", "计划详情（K6 项目成员/H1 隔离闸）"),
    
    PLANS_CASES("GET", "/api/v1/plans/{id}/cases", "计划内用例明细（K6 项目成员闸）"),
    
    PLANS_UPDATE("PUT", "/api/v1/plans/{id}", "编辑计划（K6 项目负责人角色闸）"),
 
    PLANS_ASSIGN("POST", "/api/v1/plans/{id}/assign", "计划分配执行人（K6 负责人/管理员闸）"),
    
    PLANS_PUBLISH("POST", "/api/v1/plans/{id}/publish", "发布计划（K6 项目负责人角色闸）"),
    
    PLANS_GEN_BATCHES("POST", "/api/v1/plans/{id}/generate-batches", "生成批次（K6 项目负责人角色闸）"),
    
    PLANS_COMPLETION_CHECK("GET", "/api/v1/plans/{id}/completion-check", "准出检查（K6 项目负责人角色闸）"),
    
    PLANS_CANCEL("POST", "/api/v1/plans/{id}/cancel", "取消计划（K6 项目负责人角色闸）"),
    
    PLANS_DELETE("DELETE", "/api/v1/plans/{id}", "删除计划（K6 项目负责人角色闸）"),
    
    PLANS_DEVIATION_ADD("POST", "/api/v1/plans/{id}/deviations", "偏差登记（K6 项目成员闸）"),
    
    PLANS_DEVIATION_LIST("GET", "/api/v1/plans/{id}/deviations", "偏差列表（K6 项目成员闸）"),
    
    PLANS_CASE_ALLOWED("GET", "/api/v1/plans/{id}/case-allowed", "执行资格校验（K6 项目成员闸）"),
 
    BATCHES_DELETE_TEMPLATE("DELETE", "/api/v1/batches/templates/{templateId}",
            "删除批次模板（K6 创建人/项目负责人实体级闸）"),
 
    BATCHES_TEMPLATE_SCHEDULE("PUT", "/api/v1/batches/templates/{templateId}/schedule",
            "模板定时复用配置（K6 成员 + core 创建人/负责人实体级闸）"),
 /** Batch execution cursor window over the process-event ledger (R62 B-face
  *  tail door; read-only, ownership guarded in code from the batch row —
  *  same posture as EXPLORE_SESSION_PROCESS_EVENTS, no permission key). */
    BATCH_PROCESS_EVENTS("GET", "/api/v1/batches/{id}/process-events",
            "批次执行过程事件游标窗口（只读，归属校验在码）"),
 /** R67 revisit door: per-run history rows of the batch tail, read-only,
  *  ownership guarded in code from the batch row — same posture as the
  *  cursor window above, no permission key. */
    BATCH_PROCESS_RUNS("GET", "/api/v1/batches/{id}/process-runs",
            "批次执行轮次历史（只读，归属校验在码）"),
 
    SCHEDULE_FIRES_LIST("GET", "/api/v1/schedule/fires", "定时触发留痕列表（K6 项目成员/H1 隔离闸）"),
 /** Cron next-fire preview (B-8; pure read-only computation over the caller's
     *  own cron text — touches no project resource, so no H1 guard and no
     *  permission key apply; login-only, same member-level exemption family). */
    SCHEDULE_CRON_PREVIEW("GET", "/api/v1/schedule/cron-preview", "cron 下次触发预览（登录态只读纯计算，零项目资源访问）"),
 
    REPORTS_EXPORT_TASKS("GET", "/api/v1/reports/export-tasks", "导出任务列表（K6 项目成员/H1 隔离闸）"),
    
    REPORTS_EXPORT_TASK_DOWNLOAD("GET", "/api/v1/reports/export-tasks/{id}/download",
            "导出任务下载（K6 项目成员 + H1 隔离闸）"),
 
    REPORTS_TEST_LIST("GET", "/api/v1/reports", "测试总结报告列表（K6 项目成员/H1 隔离闸）"),
    
    REPORTS_TEST_GET("GET", "/api/v1/reports/{id}", "测试总结报告详情（K6 项目成员/H1 隔离闸）"),
    
    REPORTS_TEST_EXPORT("GET", "/api/v1/reports/{id}/export", "测试总结报告导出（K6 项目成员/H1 隔离闸）"),
 
    REPORT_CENTER_LIST("GET", "/api/v1/report-center", "报表中心快照列表（K6 成员/ADMIN 数据范围闸）"),
    
    REPORT_CENTER_GET("GET", "/api/v1/report-center/{id}", "报表中心快照详情（K6 成员/ADMIN 闸）"),
    
    REPORT_CENTER_EXPORT("GET", "/api/v1/report-center/{id}/export", "报表中心导出（K6 成员/ADMIN 闸）"),
 // Program-lens dashboard feed: read-only bridge, the visible-project segment is decided
 // on core per the caller (same data-scope law as REPORT_CENTER_LIST above).
    REPORT_CENTER_PROGRAM_OVERVIEW("GET", "/api/v1/report-center/program-overview",
            "报表中心项目集总览（K6 成员/ADMIN 数据范围闸，切片判定在 core）"),
 
    TOOLS_LIST("GET", "/api/v1/tools", "工具列表（K6 项目成员/H1 隔离闸）"),
    
    TOOLS_GET("GET", "/api/v1/tools/{id}", "工具详情（K6 项目成员/H1 隔离闸）"),
 
    TOOLS_DETAIL("GET", "/api/v1/tools/{id}/detail", "工具完整详情（K6 项目成员/H1 隔离闸）"),
 
    TOOLS_VERSIONS("GET", "/api/v1/tools/{id}/versions", "工具版本历史（K6 项目成员/H1 隔离闸）"),
 
    TOOLS_VERSION_GET("GET", "/api/v1/tools/{id}/versions/{ver}", "工具单版快照（K6 项目成员/H1 隔离闸）"),
 
    TOOLS_DOWNLOAD("GET", "/api/v1/tools/{id}/download", "工具下载（K6 项目成员/H1 隔离闸，审计 core 单点）"),
 
    TOOLS_ROLLBACK("POST", "/api/v1/tools/{id}/rollback", "工具版本回滚（K6 负责人/ADMIN 角色闸）"),
    
    TOOLS_CREATE("POST", "/api/v1/tools", "新建工具（K6 负责人/ADMIN 角色闸）"),
    
    TOOLS_UPDATE("PUT", "/api/v1/tools/{id}", "编辑工具（K6 负责人/ADMIN 角色闸）"),
    
    TOOLS_DELETE("DELETE", "/api/v1/tools/{id}", "删除工具（K6 负责人/ADMIN 角色闸）"),
 
    TOOLS_SUBMIT("POST", "/api/v1/tools/{id}/submit", "提交工具审批（K6 负责人/ADMIN 角色闸）"),
    
    TOOLS_APPROVE("POST", "/api/v1/tools/{id}/approve", "工具审批通过（K6 负责人/ADMIN 角色闸）"),
    
    TOOLS_REJECT("POST", "/api/v1/tools/{id}/reject", "工具审批拒绝（K6 负责人/ADMIN 角色闸）"),
    
    TOOLS_RETIRE("POST", "/api/v1/tools/{id}/retire", "工具下架（K6 负责人/ADMIN 角色闸）"),
    
    TOOL_MARKET_IMPORT("POST", "/api/v1/tool-market/import", "工具市场导入（K6 负责人/ADMIN 角色闸）"),
    
    TOOL_MARKET_LIST("GET", "/api/v1/tool-market", "工具市场列表（登录即可，已发布只读视图）"),
    
    TOOL_MARKET_DOWNLOAD("GET", "/api/v1/tool-market/{id}/download", "工具市场下载（登录即可）"),
    
    PROJECT_TOOLS_GET("GET", "/api/v1/projects/{id}/tools", "项目工具启用集读取（K6 负责人角色闸）"),
    
    PROJECT_TOOLS_PUT("PUT", "/api/v1/projects/{id}/tools", "项目工具启用集保存（K6 负责人角色闸）"),
    
    SKILLS_CREATE("POST", "/api/v1/skills", "新建 SKILL（K6 负责人/ADMIN 角色闸）"),
    
    SKILLS_UPDATE("PUT", "/api/v1/skills/{id}", "编辑 SKILL（K6 负责人/ADMIN 角色闸）"),
    
    SKILLS_DELETE("DELETE", "/api/v1/skills/{id}", "删除 SKILL（K6 负责人/ADMIN 角色闸）"),
    
    SKILLS_SUBMIT("POST", "/api/v1/skills/{id}/submit", "提交 SKILL 审批（创建人角色闸）"),
    
    SKILLS_APPROVE("POST", "/api/v1/skills/{id}/approve", "SKILL 审批通过（K6 负责人/ADMIN 角色闸）"),
    
    SKILLS_REJECT("POST", "/api/v1/skills/{id}/reject", "SKILL 审批拒绝（K6 负责人/ADMIN 角色闸）"),
    
    SKILLS_RETIRE("POST", "/api/v1/skills/{id}/retire", "SKILL 下架（K6 负责人/ADMIN 角色闸）"),
    
    SKILL_MARKET_IMPORT("POST", "/api/v1/skill-market/import", "SKILL 市场导入（K6 负责人/ADMIN 角色闸）"),
    
    PROJECT_SKILLS_GET("GET", "/api/v1/projects/{id}/skills", "项目 SKILL 绑定集读取（K6 负责人角色闸）"),
    
    PROJECT_SKILLS_PUT("PUT", "/api/v1/projects/{id}/skills", "项目 SKILL 绑定集保存（K6 负责人角色闸）"),
 // ==== project-memory ledger write-door exemptions current
 // face = ADMIN ∪ project lead; any non-VIEW key hit-set would shrink or widen
 // that population = drift, mount forbidden — skills-domain write-path precedent.
 // Read doors mount perm:aichat:project-memory instead, not in this face) ====
    /** Manual ledger entry creation (K6 lead/ADMIN role gate). */
    PROJECT_MEMORY_CREATE("POST", "/api/v1/project-memory", "手动新增项目记忆（K6 负责人/ADMIN 角色闸）"),
    /** Enable/disable injection state (same gate). */
    PROJECT_MEMORY_STATUS("PUT", "/api/v1/project-memory/{id}/status", "项目记忆启停（K6 负责人/ADMIN 角色闸）"),
    /** Soft-delete into recycle bin (same gate). */
    PROJECT_MEMORY_DELETE("DELETE", "/api/v1/project-memory/{id}", "项目记忆删除入回收站（K6 负责人/ADMIN 角色闸）"),
    /** Restore from recycle bin (same gate). */
    PROJECT_MEMORY_RESTORE("POST", "/api/v1/project-memory/{id}/restore", "项目记忆回收站还原（K6 负责人/ADMIN 角色闸）"),
    // confirmation-door reject rides the same K6 write door as its siblings.
    PROJECT_MEMORY_REJECT("POST", "/api/v1/project-memory/{id}/reject", "项目记忆驳回待确认条目（K6 负责人/ADMIN 角色闸）"),
 
    AI_GENERATE("POST", "/api/v1/ai/generate", "AI 单条生成（K6 成员且非开发闸）"),
    
    AI_TASK_GET("GET", "/api/v1/ai/tasks/{id}", "AI 任务查询（K6 项目成员/H1 隔离闸）"),
    
    AI_TASK_RETRY("POST", "/api/v1/ai/tasks/{id}/retry", "AI 任务重试（K6 成员且非开发闸）"),
    /** Task stop door (same write law as retry; the stop semantic lives single-source in core). */
    AI_TASK_CANCEL("POST", "/api/v1/ai/tasks/{id}/cancel", "AI 任务停止（K6 成员且非开发闸）"),
    /** Generation process-event doors (02 ): read-only tail window + SSE stream;
 * no permission key — the ownership gate (project member of the task's own project)
 * runs in-code, same posture as the extraction tail doors. */
    AI_TASK_PROCESS_EVENTS("GET", "/api/v1/ai/tasks/{id}/process-events", "生成过程详情游标窗口（只读，归属校验在码）"),
    
    WEB_ELEMENT_SURVEY_PROCESS_EVENTS("GET", "/api/v1/web-elements/survey/{sessionId}/process-events", "页面测绘过程详情游标窗口（只读，归属校验在码）"),
    AI_TASK_PROCESS_EVENTS_STREAM("GET", "/api/v1/ai/tasks/{id}/process-events/stream", "生成过程详情 SSE 流（只读通道，归属校验在码）"),
 
    AI_BATCH_ESTIMATE("POST", "/api/v1/ai/batch/estimate", "批量成本预估（K6 成员且非开发闸）"),
 
    AI_BATCH_LIMIT("GET", "/api/v1/ai/batch/limit", "批量上限读取（K6 登录闸；K8 动态值展示链路）"),
    
    AI_BATCH_GENERATE("POST", "/api/v1/ai/batch-generate", "AI 批量生成（K6 成员且非开发闸）"),
    
    AI_BATCH_GET("GET", "/api/v1/ai/batches/{id}", "批量进度查询（K6 项目成员/H1 隔离闸）"),
 // Stage-0 scenario preview pair (generation-chain pre-selection draft enumeration,
 // nothing persisted as cases): run leg guarded by assertCanWriteCase (same law as
 // AI_BATCH_ESTIMATE), list leg re-reads rows first then checks row ownership with
 // assertCanView — an empty list carries no cross-project surface.
    AI_BATCH_SCENARIO_PREVIEW_RUN("POST", "/api/v1/ai/batch/scenario-preview",
            "阶段0场景预览单行运行（K6 成员且非开发闸，与批量预估同律；不落用例）"),
    AI_BATCH_SCENARIO_PREVIEWS_LIST("GET", "/api/v1/ai/batch/scenario-previews",
            "阶段0场景预览批次回读（先读回行归属再项目成员闸；空清单无越权面）"),
 
    AI_DOCUMENTS_LIST("GET", "/api/v1/ai/documents", "文档列表（K6 项目成员/H1 隔离闸）"),
    
    AI_DOCUMENTS_UPLOAD("POST", "/api/v1/ai/documents", "文档上传（K6 负责人/ADMIN 且非开发闸）"),
    
    AI_DOCUMENTS_GET("GET", "/api/v1/ai/documents/{id}", "文档详情（K6 项目成员/H1 隔离闸）"),
    
    AI_DOCUMENTS_DELETE("DELETE", "/api/v1/ai/documents/{id}", "文档删除（K6 负责人/ADMIN 且非开发闸）"),
    
    AI_DOCUMENTS_EXTRACT("POST", "/api/v1/ai/documents/{id}/extract", "需求点提取提交（K6 负责人/ADMIN 且非开发闸）"),
    
    AI_DOCUMENTS_EXTRACTION("GET", "/api/v1/ai/documents/{id}/extraction", "提取预览（K6 项目成员/H1 隔离闸）"),
    
    AI_DOCUMENTS_CHUNK("GET", "/api/v1/ai/documents/{id}/chunks/{chunkId}",
            "原文分块回读（K6 项目成员/H1 隔离闸）"),
    /** tail-f observation pair: the cursor window and the live SSE stream
 * of one extraction's process events. Both legs are read-only and run
 * assertCanView after fetching the row (same surface as the preview
 * read), so they join the ai-domain member-level exemption block. */
    AI_DOCUMENTS_EXTRACTION_EVENTS("GET", "/api/v1/ai/documents/{id}/extraction-events",
            "提取过程事件游标窗（K6 项目成员/H1 隔离闸）"),
    AI_DOCUMENTS_EXTRACTION_EVENTS_STREAM("GET", "/api/v1/ai/documents/{id}/extraction-events/stream",
            "提取过程事件实时流（K6 项目成员/H1 隔离闸）"),
    /** segment C1: channel-B spec-extraction tail window (read-only,
 * assertCanView after fetching the row — same member-level face as
 * the preview read, so it joins the same exemption block). */
    AI_DOCUMENTS_SPEC_EXTRACTION_EVENTS("GET", "/api/v1/ai/documents/{id}/spec-extraction-events",
            "接口文档抽取过程事件游标窗（K6 项目成员/H1 隔离闸）"),
    /** R67 revisit door: per-run history rows of the spec extraction tail. */
    AI_DOCUMENTS_SPEC_EXTRACTION_RUNS("GET", "/api/v1/ai/documents/{id}/spec-extraction-runs",
            "接口文档抽取轮次历史（K6 项目成员/H1 隔离闸）"),
    /** R67 revisit door: per-run history rows of the document extraction tail. */
    AI_DOCUMENTS_EXTRACTION_RUNS("GET", "/api/v1/ai/documents/{id}/extraction-runs",
            "提取轮次历史（K6 项目成员/H1 隔离闸）"),
    
    AI_DOCUMENTS_CONFIRM("POST", "/api/v1/ai/documents/{id}/confirm-extraction",
            "提取勾选入库（K6 负责人/ADMIN 且非开发闸）"),
    /** Full-store confirm : same write surface as the checkbox door. */
    AI_DOCUMENTS_CONFIRM_ALL("POST", "/api/v1/ai/documents/{id}/confirm-all-extraction",
            "提取全量入库（K6 负责人/ADMIN 且非开发闸）"),
    /** Explicit cancel of a running extraction (write door = same surface as upload/extract). */
    AI_DOCUMENTS_EXTRACTION_CANCEL("POST", "/api/v1/ai/documents/{id}/extraction-cancel",
            "取消提取（K6 负责人/ADMIN 且非开发闸）"),
    /** In-place category edit (write door = same surface as upload; lock 40901 is decided by core alone). */
    AI_DOCUMENTS_UPDATE_CATEGORY("PATCH", "/api/v1/ai/documents/{id}/category",
            "文档分类行内编辑（K6 负责人/ADMIN 且非开发闸）"),
    /** In-place usage-switch group edit (write door = same surface as upload; scope = one row). */
    AI_DOCUMENTS_UPDATE_USAGE_SWITCHES("PATCH", "/api/v1/ai/documents/{id}/usage-switches",
            "文档用途开关行内编辑（K6 负责人/ADMIN 且非开发闸）"),
 
    DOC_CHANGE_ANALYSES_LIST("GET", "/api/v1/doc-change-analyses",
            "变更分析复核队列（K6 项目成员/H1 隔离闸）"),
    
    DOC_CHANGE_ANALYSES_REANALYZE("POST", "/api/v1/doc-change-analyses/reanalyze",
            "文档重传变更分析触发（K6 负责人/ADMIN 且非开发闸）"),
    
    DOC_CHANGE_ANALYSES_HANDLE("POST", "/api/v1/doc-change-analyses/{id}/handle",
            "变更分析处置留痕（行归属反查写闸）"),
    /** Pre-generate anchored revision-draft cards when the disposition view
     *  opens (write gate = the row's owning project, same shape as handle). */
    DOC_CHANGE_ANALYSES_PREGEN_DRAFTS("POST", "/api/v1/doc-change-analyses/{id}/pregen-drafts",
            "Requirement revision-draft pre-generation (row-ownership write gate)"),
 
    API_SPECS_LIST("GET", "/api/v1/api-specs", "接口契约列表（K6 项目成员/H1 隔离闸）"),
    
    API_SPECS_UPLOAD("POST", "/api/v1/api-specs", "接口契约上传（K6 负责人/ADMIN 且非开发闸）"),
 
    API_SPECS_FETCH_URL("POST", "/api/v1/api-specs/fetch-url",
            "接口契约 URL 拉取（K6 负责人/ADMIN 且非开发闸）"),
    
    API_SPECS_HISTORY("GET", "/api/v1/api-specs/history", "契约版本链（K6 项目成员/H1 隔离闸）"),
    
    API_SPECS_GET("GET", "/api/v1/api-specs/{id}", "契约版本详情（行归属反查成员闸）"),
 
    API_SPEC_SUBS_CREATE("POST", "/api/v1/api-specs/subscriptions",
            "契约订阅新建（K6 负责人/ADMIN 且非开发闸）"),
    
    API_SPEC_SUBS_LIST("GET", "/api/v1/api-specs/subscriptions",
            "契约订阅列表（K6 项目成员/H1 隔离闸）"),
    
    API_SPEC_SUBS_UPDATE("PUT", "/api/v1/api-specs/subscriptions/{id}",
            "契约订阅编辑（K6 负责人/ADMIN 且非开发闸·行归属反查）"),
    
    API_SPEC_SUBS_DELETE("DELETE", "/api/v1/api-specs/subscriptions/{id}",
            "契约订阅删除（K6 负责人/ADMIN 且非开发闸·行归属反查）"),
 
    API_SPEC_PREVIEWS_GENERATE("POST", "/api/v1/api-spec-previews/generate/{specId}",
            "导入预览生成（K6 负责人/ADMIN 且非开发闸·行归属反查）"),
    
    API_SPEC_PREVIEWS_GET("GET", "/api/v1/api-spec-previews/{id}",
            "导入预览详情（K6 项目成员/H1 隔离闸·行归属反查）"),
    
    API_SPEC_PREVIEWS_APPLY("POST", "/api/v1/api-spec-previews/{id}/apply",
            "导入勾选写入（K6 负责人/ADMIN 且非开发闸·行归属反查）"),
    
    API_SPEC_PREVIEWS_EXTRACT("POST", "/api/v1/api-spec-previews/extract-from-document",
            "接口设计文档抽取触发（K6 负责人/ADMIN 且非开发闸·文档归属反查）"),
    
    API_SPEC_PREVIEWS_BY_DOC("GET", "/api/v1/api-spec-previews/by-document/{documentId}",
            "抽取结果按文档查询（K6 项目成员/H1 隔离闸·文档归属反查）"),
    // Change-review queue endpoints: the authoritative checks stay in the
    // ProjectAccessGuard (read paths = assertCanView members/H1, write paths =
    // assertCanWriteRequirement with row-ownership reverse lookup), same shape
    // as the F3 doc-change-analyses precedent — no catalog key overlaps both
    // guard sides, so these register as member-level exemptions.
    /** Pending-disposition queue page (project members, H1). */
    API_SPEC_CHANGE_ANALYSES_LIST("GET", "/api/v1/api-spec-change-analyses",
            "契约变更分析队列分页（K6 项目成员/H1 隔离闸）"),
    /** Queue row detail (member read after the row's project is resolved). */
    API_SPEC_CHANGE_ANALYSES_GET("GET", "/api/v1/api-spec-change-analyses/{id}",
            "队列行详情（行归属反查成员闸）"),
    /** Analysis row by new spec id (read-side twin of the single trigger door). */
    API_SPEC_CHANGE_ANALYSES_BY_SPEC("GET", "/api/v1/api-spec-change-analyses/by-spec/{newSpecId}",
            "按新版契约取分析行（行归属反查成员闸）"),
    /** Trigger re-analysis (requirement-write gate of the owning project). */
    API_SPEC_CHANGE_ANALYSES_REANALYZE("POST", "/api/v1/api-spec-change-analyses/reanalyze",
            "契约变更分析触发（K6 负责人/ADMIN 且非开发闸）"),
    /** Disposition trace on one row (write gate after row-ownership lookup). */
    API_SPEC_CHANGE_ANALYSES_HANDLE("POST", "/api/v1/api-spec-change-analyses/{id}/handle",
            "变更分析处置留痕（行归属反查写闸）"),
    /** All-or-nothing batch disposition (same write gate per row). */
    API_SPEC_CHANGE_ANALYSES_HANDLE_BATCH("POST", "/api/v1/api-spec-change-analyses/handle-batch",
            "变更分析批量处置（K6 负责人/ADMIN 且非开发闸·逐行归属反查）"),
 
    DATA_FACTORIES_LIST("GET", "/api/v1/data-factories", "造数脚本列表（K6 项目成员/H1 隔离闸）"),
    
    DATA_FACTORIES_CREATE("POST", "/api/v1/data-factories", "创建造数脚本（K6 负责人/ADMIN 角色闸）"),
    
    DATA_FACTORIES_GET("GET", "/api/v1/data-factories/{id}", "造数脚本详情（K6 项目成员/H1 隔离闸）"),
    
    DATA_FACTORIES_UPDATE("PUT", "/api/v1/data-factories/{id}", "编辑造数脚本（K6 负责人/ADMIN 角色闸）"),
    
    DATA_FACTORIES_DELETE("DELETE", "/api/v1/data-factories/{id}", "删除造数脚本（K6 负责人/ADMIN 角色闸）"),
 
    DATA_FACTORIES_RUN("POST", "/api/v1/data-factories/{id}/run", "造数一键执行（K6 项目成员闸）"),
    
    DATA_FACTORIES_RUNS("GET", "/api/v1/data-factories/{id}/runs", "造数运行历史（K6 项目成员/H1 隔离闸）"),
    
    DATA_FACTORIES_RECENT_RUNS("GET", "/api/v1/data-factories/{id}/recent-runs",
            "造数最近运行（K6 项目成员/H1 隔离闸）"),
 
    DATA_POOL_ITEMS_LIST("GET", "/api/v1/data-factories/pool-items", "数据池列表（K6 项目成员/H1 隔离闸）"),
    
    DATA_POOL_ITEMS_STATUS("PUT", "/api/v1/data-factories/pool-items/{id}/status",
            "数据池状态更新（K6 负责人/ADMIN 角色闸）"),
    
    DATA_POOL_ITEMS_DELETE("DELETE", "/api/v1/data-factories/pool-items/{id}",
            "数据池项删除（K6 负责人/ADMIN 角色闸）"),
 
    DATA_FACTORIES_GENKIT_CATALOG("GET", "/api/v1/data-factories/genkit-catalog",
            "genkit 函数只读目录（全局静态投影，无项目维度）"),
 
    REQUIREMENTS_PROJECT_LIST("GET", "/api/v1/projects/{id}/requirements",
            "需求点列表（K6 项目成员/H1 隔离闸）"),
    
    REQUIREMENTS_PROJECT_CREATE("POST", "/api/v1/projects/{id}/requirements",
            "创建需求点（K6 负责人/ADMIN 且非开发闸）"),
    
    REQUIREMENTS_PROJECT_UPDATE("PUT", "/api/v1/projects/{id}/requirements/{id}",
            "编辑需求点（K6 负责人/ADMIN 且非开发闸）"),
    
    REQUIREMENTS_PROJECT_DELETE("DELETE", "/api/v1/projects/{id}/requirements/{id}",
            "删除需求点（K6 负责人/ADMIN 且非开发闸）"),
 
    REQUIREMENTS_LINK_CASES("POST", "/api/v1/requirements/{id}/cases",
            "需求点关联用例（K6 成员且非开发闸）"),
    
    REQUIREMENTS_UNLINK_CASE("DELETE", "/api/v1/requirements/{id}/cases/{id}",
            "需求点取消关联（K6 成员且非开发闸）"),
 
    CASES_UNLINK_REQUIREMENT("DELETE", "/api/v1/cases/{id}/requirements/{id}",
            "用例侧取消关联（K6 成员且非开发闸）"),
    
    CASES_REQUIREMENTS_LIST("GET", "/api/v1/cases/{id}/requirements",
            "用例已关联需求点列表（K6 项目成员/H1 隔离闸）"),
    
    REQUIREMENTS_LINKED_CASES("GET", "/api/v1/requirements/{id}/cases",
            "需求点已关联用例列表（K6 项目成员/H1 隔离闸）"),
 // Case-gap page behind the generation-entry chip: project-scoped read-only list of
 // requirements without an active case link (guard.assertCanView, same law as
 // REQUIREMENTS_PROJECT_LIST); switch-off refusal passes through from core.
    REQUIREMENTS_PROJECT_UNCOVERED("GET", "/api/v1/projects/{id}/requirements/uncovered",
            "未有用例的需求点分页（K6 项目成员/H1 隔离闸；生成入口 chips 数据源）"),
 
    COST_OVERVIEW("GET", "/api/v1/cost/overview", "成本聚合总览（ADMIN 全局/K6 项目负责人链路）"),
    
    COST_BUDGET_GET("GET", "/api/v1/cost/budget/{id}", "项目预算读取（K6 负责人/ADMIN 角色闸）"),
    
    COST_BUDGET_PUT("PUT", "/api/v1/cost/budget/{id}", "覆写项目预算（K6 负责人/ADMIN 角色闸）"),
 
    PROJECT_LLM_CONFIG_GET("GET", "/api/v1/projects/{id}/llm-config", "项目大模型配置读取（K6 项目成员闸）"),
    
    PROJECT_LLM_CONFIG_PUT("PUT", "/api/v1/projects/{id}/llm-config", "项目大模型选用（K6 负责人/ADMIN 角色闸）"),
 
    NOTIFICATIONS_MARK_READ("PUT", "/api/v1/notifications/{id}/read", "标记已读（本人归属判定在业务层）"),
 
    NOTIFICATIONS_MARK_ALL_READ("PUT", "/api/v1/notifications/read-all", "全部已读（本人归属判定在业务层）"),
 
    API_TOKENS_LIST("GET", "/api/v1/api-tokens", "本人 Token 列表（属主自助，判定单点在 core）"),
    
    API_TOKENS_CREATE("POST", "/api/v1/api-tokens", "创建本人 Token（属主自助）"),
    
    API_TOKENS_REVOKE("DELETE", "/api/v1/api-tokens/{id}/revoke", "吊销 Token（本人+ADMIN 判定在 core）"),
    /** GA3 per-token usage read (owner ∪ admin read-through decided once in core; binding a MANAGE key would shrink the self-service surface, so it stays exempt). */
    API_TOKENS_USAGE("GET", "/api/v1/api-tokens/{id}/usage", "Token 调用统计（属主∪代看判定单点在 core）"),
 
    IMPORT_CASES("POST", "/api/v1/import/cases", "用例批量导入（K6 成员且非开发闸）"),
    
    IMPORT_CASES_RETRY("POST", "/api/v1/import/cases/retry", "用例导入失败重试（K6 成员且非开发闸）"),
    
    IMPORT_REQUIREMENTS("POST", "/api/v1/import/requirements", "需求点批量导入（K6 负责人/ADMIN 且非开发闸）"),
 
    DELETE_REQUESTS_LIST("GET", "/api/v1/delete-requests", "删除申请列表（K6 项目成员/H1 隔离闸）"),
    
    DELETE_REQUESTS_CREATE("POST", "/api/v1/delete-requests", "提交删除申请（K6 负责人/ADMIN 且非开发闸）"),
    
    DELETE_REQUESTS_APPROVE("POST", "/api/v1/delete-requests/{id}/approve", "删除申请审批通过（K6 负责人/ADMIN 闸）"),
    
    DELETE_REQUESTS_REJECT("POST", "/api/v1/delete-requests/{id}/reject", "删除申请审批拒绝（K6 负责人/ADMIN 闸）"),
 
    SUITES_LIST("GET", "/api/v1/suites", "回归集列表（K6 项目成员/H1 隔离闸）"),
    
    SUITES_CREATE("POST", "/api/v1/suites", "创建回归集（K6 负责人/ADMIN 角色闸）"),
    
    SUITES_GET("GET", "/api/v1/suites/{id}", "回归集详情（K6 项目成员/H1 隔离闸）"),
    
    SUITES_UPDATE("PUT", "/api/v1/suites/{id}", "编辑回归集（K6 负责人/ADMIN 角色闸）"),
    
    SUITES_DELETE("DELETE", "/api/v1/suites/{id}", "删除回归集（K6 负责人/ADMIN 角色闸）"),
 
    SUITES_RUN("POST", "/api/v1/suites/{id}/run", "回归集一键生成计划（K6 负责人/ADMIN 角色闸）"),
 
    EXECUTORS_TASKS_LIST("GET", "/api/v1/executors/tasks", "试运行任务列表（K6 项目成员/H1 隔离闸）"),
    
    EXECUTORS_TASK_GET("GET", "/api/v1/executors/tasks/{id}", "试运行任务详情（K6 项目成员/H1 隔离闸）"),
    
    EXECUTORS_TASK_SCREENSHOTS("GET", "/api/v1/executors/tasks/{id}/screenshots",
            "截图预览（K6 项目成员/H1 隔离闸）"),
 
    EXECUTORS_TASK_STEP_SNAPSHOTS("GET", "/api/v1/executors/tasks/{id}/step-snapshots",
            "步骤协议快照（K6 项目成员/H1 隔离闸）"),
 
    EXECUTORS_TASK_STEP_EVIDENCE("GET", "/api/v1/executors/tasks/{id}/step-evidence",
            "步骤证据（K6 项目成员/H1 隔离闸）"),
 
    EXECUTORS_TASK_MONITOR_FRAMES("GET", "/api/v1/executors/tasks/{id}/monitor-frames",
            "监看帧轮询（K6 项目成员/H1 隔离闸）"),
 
    EXPLORE_SESSIONS_LIST("GET", "/api/v1/explore/sessions", "探索会话列表（K6 项目成员/H1 隔离闸）"),
    
    EXPLORE_SESSION_GET("GET", "/api/v1/explore/sessions/{id}", "探索会话详情（K6 项目成员/H1 隔离闸）"),
    EXPLORE_SESSION_PROCESS_EVENTS("GET", "/api/v1/explore/sessions/{id}/process-events",
            "探索会话过程事件游标窗口（只读，归属校验在码）"),
 /** R67 revisit door: per-run history rows of the explore session tail,
  *  read-only, ownership guarded in code from the session row. */
    EXPLORE_SESSION_PROCESS_RUNS("GET", "/api/v1/explore/sessions/{id}/process-runs",
            "探索会话轮次历史（只读，归属校验在码）"),
    
    EXPLORE_STEP_SCREENSHOT("GET", "/api/v1/explore/sessions/{id}/steps/{seq}/screenshot",
            "探索单步取证预签名（K6 项目成员/H1 隔离闸）"),
 
    EXPLORE_SESSION_DELETE("DELETE", "/api/v1/explore/sessions/{id}",
            "探索会话取消/清理（创建人/ADMIN 角色闸）"),
    
    PROJECT_PLUGIN_APPLY("POST", "/api/v1/projects/{id}/plugins/{id}/apply",
            "项目装配插件（K6 负责人/ADMIN 角色闸）"),
 
    PROJECT_PLUGIN_UNINSTALL("DELETE", "/api/v1/projects/{id}/plugins/{id}",
            "项目卸载插件（K6 负责人/ADMIN 角色闸）"),
    
    PROJECT_PLUGIN_LIST("GET", "/api/v1/projects/{id}/plugins",
            "项目已装配插件清单（K6 成员可读）");

    private final String method;
    private final String pathPattern;
    private final String reason;
    
    private final Pattern compiled;

    PublicEndpointRegistry(String method, String pathPattern, String reason) {
        this.method = method;
        this.pathPattern = pathPattern;
        this.reason = reason;
        this.compiled = pathPattern.endsWith("/**") ? null : compilePath(pathPattern);
    }

    private static Pattern compilePath(String pattern) {
        Matcher seg = Pattern.compile("\\{[^/}]+}").matcher(pattern);
        StringBuilder regex = new StringBuilder();
        int last = 0;
        while (seg.find()) {
            regex.append(Pattern.quote(pattern.substring(last, seg.start())));
            regex.append("id".equals(seg.group().substring(1, seg.group().length() - 1))
                    ? "\\d+" : "[^/]+");
            last = seg.end();
        }
        regex.append(Pattern.quote(pattern.substring(last)));
        return Pattern.compile(regex.toString());
    }

    public String getMethod() {
        return method;
    }

    public String getPathPattern() {
        return pathPattern;
    }

    public String getReason() {
        return reason;
    }

    
    public static boolean isPublic(String requestMethod, String requestPath) {
        for (PublicEndpointRegistry entry : values()) {
            if (entry.matches(requestMethod, requestPath)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(String requestMethod, String requestPath) {
        if (!"*".equals(method) && !method.equals(requestMethod)) {
            return false;
        }
        if (compiled == null) {
            String prefix = pathPattern.substring(0, pathPattern.length() - 3);
            return requestPath.startsWith(prefix + "/");
        }
        return compiled.matcher(requestPath).matches();
    }
}
