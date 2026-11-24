package com.sixbean.testpike.core.apitest.domain.component;

/**
 * Single-source refusal wording for the promotion doors (single request
 * promote and whole-flow promote) under the R63 smart-guidance contract.
 * Every user-reachable refusal raised while promoting an API-test request
 * into a regression case is phrased here, so one L2 test can pin the three
 * elements (offending value echoed, on-screen repair entry quoted, action
 * word for the next step) without booting infrastructure, and so the same
 * sentence never lives in two call sites.
 *
 * <p>The quoted literals must match the shipped frontend wording: the API
 * test rail entry, the new-request button, the auth/token/header-name/
 * assertion-group/precondition/loop field labels and the case-management
 * list entry. Keep them in sync when the screens change.
 */
public final class PromotionRefusals {

    private PromotionRefusals() {
    }

    public static String alreadyPromoted(String caseNo) {
        return "该请求已升格为用例「" + caseNo + "」，无需重复升格；如需更新用例内容，"
                + "请到「用例管理」的「用例列表」搜索 " + caseNo + " 后编辑该用例";
    }

    public static String concurrentPromoted(String caseNo) {
        return "该请求刚刚被并发升格（用例「" + caseNo + "」），本次未重复建用例；"
                + "请到「用例管理」的「用例列表」搜索 " + caseNo + " 查看即可";
    }

    public static String emptyCollection(String collectionName) {
        return "集合「" + collectionName + "」还没有请求，空流程没有可升格的内容；"
                + "请在「接口测试」左侧选中该集合后点「新建请求」补齐请求，再发起整流程升格";
    }

    public static String memberAlreadyPromoted(String memberName, String caseNo) {
        return "请求「" + memberName + "」已升格为用例「" + caseNo + "」，整条流程未重复升格；"
                + "如需更新用例内容请到「用例管理」的「用例列表」编辑该用例，"
                + "或从集合「" + memberName + "」所在流程中摘除该成员后再升格其余请求";
    }

    public static String memberUnsupportedProtocol(String memberName, String protocol) {
        return "请求「" + memberName + "」使用 " + protocol + " 传输，用例步骤暂不支持该协议，"
                + "整条流程未升格；请在「接口测试」打开该集合摘除「" + memberName + "」后"
                + "再次发起整流程升格，其余请求即可生成用例";
    }

    public static String memberOrchestration(String memberName) {
        return "请求「" + memberName + "」配有「前置条件」或「循环」编排，升格后执行语义会失真，"
                + "整条流程未升格；请在「接口测试」打开该请求摘除「前置条件」「循环」后重试，"
                + "或让该请求保留在接口测试中运行";
    }

    /** The mapper reason is appended verbatim so the row-level cause stays visible. */
    public static String memberAssertionViolation(String memberName, String violation) {
        return "请求「" + memberName + "」的「断言组」无法映射为用例步骤：" + violation
                + "；请在「接口测试」打开该请求修正「断言组」后重新升格";
    }

    public static String memberConcurrentPromoted(String memberName, String caseNo) {
        return "请求「" + memberName + "」刚刚被并发升格（用例「" + caseNo + "」），"
                + "整条流程未重复建用例；请到「用例管理」的「用例列表」搜索 " + caseNo
                + " 查看，或摘除该成员后再次发起整流程升格";
    }

    public static String bearerTokenMissing(String requestName) {
        return "请求「" + requestName + "」的「认证」选择了 Bearer 但「令牌」为空，"
                + "升格后步骤将没有认证可发；请在「接口测试」打开该请求，在「认证」里填写「令牌」"
                + "（或选择其它认证方式）后重新升格";
    }

    public static String headerNameMissing(String requestName) {
        return "请求「" + requestName + "」的「认证」选择了 Header 但「头名」为空，"
                + "无法组装步骤请求头；请在「接口测试」打开该请求，在「认证」里填写「头名」与取值后重新升格";
    }

    public static String authTypeUnsupported(String type, String requestName) {
        return "请求「" + requestName + "」的认证类型「" + type + "」暂不支持升格（当前支持 "
                + "Bearer / Header），继续会丢失认证导致用例静默裸跑，已停止；请在「接口测试」打开该请求"
                + "把「认证」改为支持的方式后重新升格";
    }

    public static String envelopeAssemblyFailed(String subjectName) {
        return "「" + subjectName + "」的升格步骤动作组装失败（请求定义中存在无法组装的内容）；"
                + "请在「接口测试」打开该请求检查「断言组」「请求头」等配置并重新保存后再次升格";
    }

    public static String invalidJson(String label, String requestName) {
        return "请求「" + requestName + "」的「" + label + "」内容不是合法 JSON，无法升格；"
                + "请在「接口测试」打开该请求，重新填写并保存「" + label + "」后重试";
    }
}
