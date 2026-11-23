package com.sixbean.testpike.common.rbac;


public enum PermissionDomain {

    
    CASE("case", "用例生成"),
    
    SCRIPT("script", "脚本生成"),
    
    REVIEW("review", "评审辅助"),
    
    FEEDBACK("feedback", "反馈与学习"),
    
    PLATFORM("platform", "平台治理"),
    
    DOCUMENT("document", "文档分析"),
    
    KNOWLEDGE("knowledge", "知识库与MCP集成"),
    
    PROJECT("project", "多项目与租户隔离"),
    
    BATCH("batch", "长程任务与批次调度"),
    
    SYSTEM("system", "平台能力与独立开源"),
    
    TESTOPS("testops", "测试运营"),
    
    DETAIL("detail", "细节逻辑"),
    
    SECURITY("security", "运营安全"),
    
    UX("ux", "体验优化"),
    
    SCENARIO("scenario", "场景二轮"),
    
    SAFETYOPS("safetyops", "安全运维"),
 
    EXEC("exec", "执行与进程管理"),
    
    SKILL("skill", "SKILL能力"),
    
    TOOL("tool", "Tools工具"),
 
    COMMENT("comment", "评论与协同"),
 
    APPAUTO("appauto", "APP 自动化"),
 
    CLUSTER("cluster", "机群与装机"),
 /** API mock simulation domain (AG batch; key family perm:mock:{action}). */
    MOCK("mock", "接口仿真"),
 /** API test asset domain (AH batch; contract inventory door). */
    APITEST("apitest", "API 测试资产"),
 /** Online dialog domain (AO batch): the door key for the chat dock and the
     * SSE stream/abort endpoints. */
    AICHAT("aichat", "在线 AI 对话"),
 /** Shared asset pool domain (Y wave ): cross-project published assets
 * (case fragments, action components, login recipes, gen functions). */
    ASSET("asset", "共享资产池"),
 /** Online AI-evaluation facade : evaluation-set management,
 * AI question generation and judge-run report doors. */
    JUDGE("judge", "AI 评测"),
 /** Session login-state capture & reuse : manual-assisted
 * capture, ledger read/manage, pre-check verify and consumer doors. */
    SESSION("session", "登录态采集与复用");

    private final String token;
    private final String label;

    PermissionDomain(String token, String label) {
        this.token = token;
        this.label = label;
    }

    
    public String token() {
        return token;
    }

    
    public String label() {
        return label;
    }
}
