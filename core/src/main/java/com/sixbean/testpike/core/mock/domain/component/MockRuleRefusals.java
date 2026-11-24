package com.sixbean.testpike.core.mock.domain.component;

import java.util.List;

/**
 * Single-source refusal wording for the mock save gate (simulation space form
 * and simulation rule form) under the R63 smart-guidance contract. Every
 * user-reachable refusal raised while saving a space or a rule is phrased
 * here, so one L2 test can pin the three elements (offending value echoed,
 * on-screen repair entry quoted, action word for the next step) without
 * booting infrastructure, and so the same sentence never lives in two call
 * sites.
 *
 * <p>The quoted literals inside each sentence must match the shipped frontend
 * wording: the simulation-space page and its create-space dialog (name,
 * routing handle, alias, rate limit, daily quota, chaos dial, base-path mode,
 * auth mode, miss-policy ladder), the simulation-docs page and its structured
 * rule form (rule name, request method, path, path-match type, matcher
 * block, response block) and the state-scenario page with its create dialog
 * (scenario name). Keep them in sync when the screens change.
 */
public final class MockRuleRefusals {

    /** Repair entry quoted for every field of the space dialog. */
    public static final String SPACE_ENTRY = "「仿真空间」页的「新建空间」";

    /** Repair entry quoted for every field of the rule form. */
    public static final String RULE_ENTRY = "「仿真文档」页的「结构化新建规则」（改已有规则点该行「编辑」）";

    /** Repair entry quoted for scenario-level fields. */
    public static final String SCENARIO_ENTRY = "「状态场景」页的「新建场景」";

    /**
     * Repair entry quoted for mock-space binding refusals raised by the
     * case/regression-suite save chains (the picker lives on those forms,
     * the published spaces themselves are listed on the space page).
     */
    public static final String BINDING_ENTRY = "「仿真空间」页";

    private MockRuleRefusals() {
    }

    public static String spaceKeyIllegal(String key) {
        return "「路由标识（spaceKey）」填的「" + key + "」不符合字符规则：只能用小写字母、数字、下划线和连字符，"
                + "且以字母或数字开头（它会进入访问路径 /mocks/ 之下）；请在" + SPACE_ENTRY
                + "里把「路由标识（spaceKey）」改成形如 order-api 的值后重新提交";
    }

    public static String spaceAliasIllegal(String alias) {
        return "「别名（可选）」填的「" + alias + "」不符合字符规则（与「路由标识（spaceKey）」相同：小写字母、数字、"
                + "下划线、连字符，且以字母或数字开头）；请在" + SPACE_ENTRY
                + "里把「别名（可选）」改成形如 legacy-alias 的值，或清空该栏后重新提交";
    }

    public static String rateLimitNotPositive(int rateLimitRps) {
        return "「限流 RPS（留空走默认）」填的 " + rateLimitRps
                + " 不是正数，无法作为每秒请求上限；请在" + SPACE_ENTRY
                + "里把「限流 RPS（留空走默认）」改成大于 0 的整数，或清空该栏走系统默认后重新提交";
    }

    public static String dailyQuotaNotPositive(long quotaDaily) {
        return "「日配额（留空走默认）」填的 " + quotaDaily
                + " 不是正数，无法作为每天允许的调用总量；请在" + SPACE_ENTRY
                + "里把「日配额（留空走默认）」改成大于 0 的整数，或清空该栏走系统默认后重新提交";
    }

    public static String chaosOutOfRange(int chaosPercent) {
        return "「混沌比例（%）」填的 " + chaosPercent
                + " 落在合法区间 0-100 之外，按比例注入故障无法解释这个数；请在" + SPACE_ENTRY
                + "里把「混沌比例（%）」改成 0-100 之间的整数（0 表示不注入）后重新提交";
    }

    public static String pathPatternMissingSlash(String pathPattern) {
        return "「路径」填的「" + pathPattern + "」没有以 / 开头，数据面无法把它挂到空间路由根下；请在"
                + RULE_ENTRY + "里把「路径」改成以 / 开头的形如 /orders/{id} 的值后重新提交";
    }

    public static String regexPathUncompilable(String pathPattern, String compilerReason) {
        return "「路径匹配方式」选了正则时，「路径」里的「" + pathPattern + "」编译不过：" + compilerReason
                + "；请在" + RULE_ENTRY + "里把「路径」改成正则可解析的写法（例如 /orders/\\d+）后重新提交，"
                + "或把「路径匹配方式」换成「精确匹配」再保存";
    }

    public static String templateBracesUnpaired(String pathPattern) {
        return "「路径」填的「" + pathPattern + "」花括号不成对，模板路径无法拆出参数名；请在" + RULE_ENTRY
                + "里把它改成成对的写法（例如 /orders/{orderId}/items/{itemId}）后重新提交";
    }

    public static String scenarioNameTooLong(int actualLength) {
        return "「场景名称」当前 " + actualLength + " 个字符，超出上限 80 个字符，列表与状态机图都放不下；请在"
                + SCENARIO_ENTRY + "里把「场景名称」缩短到 80 个字符以内（例如 订单状态机-主流程）后重新提交";
    }

    public static String matchersMustBeArray(String offendingValue) {
        return "「匹配条件」的内容「" + offendingValue + "」不是 JSON 数组，匹配器要求每条一行；请在" + RULE_ENTRY
                + "里把「匹配条件」写成数组形态（例如 [{\"field\":\"header.X-Trace\",\"op\":\"equals\",\"value\":\"t1\"}]）后重新提交";
    }

    public static String responseMustBeObject(String offendingValue) {
        return "「响应行为」的内容「" + offendingValue + "」不是 JSON 对象，出参需要 status/headers/body 这些键；请在"
                + RULE_ENTRY + "里把「响应行为」写成对象形态（例如 {\"status\":200,\"body\":{\"code\":0}}）后重新提交";
    }

    public static String jsonMalformed(String fieldLabel, String offendingValue, String parserReason) {
        return fieldLabel + "的内容「" + trimForDisplay(offendingValue) + "」不是合法 JSON：" + parserReason
                + "；请在" + RULE_ENTRY + "里修正该栏内容（可在编辑器里校验 JSON 语法后）重新提交";
    }

    public static String blankRequired(String fieldLabel, String entry) {
        return fieldLabel + "不能为空，保存闸没有它无法落库；请在" + entry + "里填写" + fieldLabel + "后重新提交";
    }

    public static String tooLong(String fieldLabel, int maxLen, int actualLen) {
        return fieldLabel + "填了 " + actualLen + " 个字符，超出上限 " + maxLen
                + " 个字符，列表列宽与检索都会变形；请在对应表单里把" + fieldLabel + "缩短到 " + maxLen
                + " 个字符以内后重新提交";
    }

    public static String valueNotAllowed(String fieldLabel, String value, List<String> allowed, String entry) {
        return fieldLabel + "不支持「" + (value == null ? "（留空）" : value) + "」，可选值："
                + String.join(" / ", allowed) + "；请在" + entry + "里把" + fieldLabel
                + "改成上述可选值之一后重新提交";
    }

    public static String bindingSpaceMissing(long spaceId) {
        return "挂载的仿真空间不存在（ID=" + spaceId + "），可能已被删除；请在" + BINDING_ENTRY
                + "确认空间仍在清单里，再回到用例/回归集的「仿真空间」下拉重新选择后保存";
    }

    public static String bindingSpaceOtherProject(long spaceId) {
        return "ID=" + spaceId + " 的仿真空间不属于当前项目，跨项目挂载被拦截（空间只服务归属项目的流量）；"
                + "请在「仿真空间」下拉里改选当前项目自己的空间，或到" + BINDING_ENTRY
                + "为本项目新建并发布一个后重新保存";
    }

    public static String bindingSpaceDraft(long spaceId) {
        return "ID=" + spaceId + " 的仿真空间还是「草稿」状态，发布前不对外服务、没有可运行的规则；"
                + "请到" + BINDING_ENTRY + "找到该行点「发布空间」，再回到用例/回归集重新保存（也可先改挂其他已发布空间）";
    }

    public static String bindingSpaceRetired(long spaceId) {
        return "ID=" + spaceId + " 的仿真空间" + "「已退役」是终态，不再对外服务，新绑定不允许指向退役空间；"
                + "请在「仿真空间」下拉里改挂其他已发布空间，或到" + BINDING_ENTRY
                + "复制该空间生成新空间后重新保存";
    }

    public static String stubVersionStale(long stubId, int expectedVersion) {
        return "规则（ID=" + stubId + "）已被其他人修改，你提交的版本号 " + expectedVersion
                + " 已过期，保存没有落笔以免覆盖别人的改动；请到「仿真文档」页该行点「编辑」重新打开最新内容，再提交你的修改";
    }

    public static String stubMissing(long stubId) {
        return "找不到 ID=" + stubId + " 的仿真规则，可能已被删除；请到「仿真文档」页的规则列表按名称确认该行是否还在，"
                + "不在则用「结构化新建规则」重新创建一条";
    }

    public static String stateColumnsWithoutScenario() {
        return "「触发状态」/「推进状态」填了值，但「场景名称」是空的——没有状态机，这两个字段永远不会被触发；"
                + "请在「结构化新建规则」/「编辑」表单里填写「场景名称」绑定本空间的场景，"
                + "或清空「触发状态」与「推进状态」后重新保存";
    }

    public static String stubSpaceMissing(long spaceId) {
        return "找不到 ID=" + spaceId + " 的仿真空间，规则没有可归属的空间；请到" + BINDING_ENTRY
                + "用「新建空间」先创建（或恢复）该空间，再回到「仿真文档」页配置规则";
    }

    public static String scenarioGhost(String scenarioName) {
        return "场景「" + scenarioName + "」在本空间不存在——绑了不存在的状态机，规则永远不会触发；"
                + "请到" + SCENARIO_ENTRY + "先创建该场景，或回到" + RULE_ENTRY
                + "把「场景名称」清空后重新保存";
    }

    /** Keeps the echoed payload readable when a whole document was submitted. */
    private static String trimForDisplay(String value) {
        String text = value == null ? "" : value.trim();
        return text.length() <= 80 ? text : text.substring(0, 80) + "…";
    }
}
