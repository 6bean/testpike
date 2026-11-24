package com.sixbean.testpike.core.project.domain.component;

/**
 * Single-source refusal wording for the project environment door
 * (R63 smart-guidance contract). Every user-reachable refusal raised by
 * the environment service is phrased here so one L2 test can pin the
 * three elements (offending value echoed, on-screen repair entry quoted,
 * no dead-end pointer) without booting infrastructure, and so the same
 * sentence never lives in two call sites (R40).
 *
 * <p>The quoted literals must match the shipped frontend wording: the
 * environment tab, the create/edit buttons, the five field labels, the
 * deploy-record dialog title and the forced-delete hint text. The L2
 * contract test pins every quoted label against the locale file, so this
 * comment carries no wording of its own that could drift.
 */
public final class ProjectEnvRefusals {

    private ProjectEnvRefusals() {
    }

    /** Plain-language env-type label as the dialog dropdown shows it. */
    public static String envTypeLabel(int envType) {
        return switch (envType) {
            case 1 -> "开发环境";
            case 2 -> "测试环境";
            case 3 -> "预发环境";
            case 4 -> "生产环境";
            default -> "未知类型";
        };
    }

    public static String duplicateEnvName(String name) {
        return "该项目已存在同名环境「" + name + "」；请到「环境配置」页签该行点「编辑环境」改「环境名称」后保存，或在「新增环境」里换一个名字";
    }

    public static String envMissing() {
        return "环境不存在或不属于当前项目；请回到项目「环境配置」页签核对该行是否仍在列表，若已被删除可点「新增环境」重建";
    }

    public static String envGoneById(long envId) {
        return "环境（ID " + envId + "）不存在或已被删除；请到「环境配置」页签确认该行是否仍在列表，不在则点「新增环境」重建";
    }

    /** %s is filled with the reference list by the caller. */
    public static String deleteBlocked() {
        return "该环境被 %s 引用，无法删除；请先解除上述引用，或再次发起删除并在「删除将清空相关执行批次的该环境引用，且不可恢复。确定继续吗？」下点「确定」（入口：「环境配置」行操作「删除」）";
    }

    public static String projectMissing(long projectId) {
        return "项目（ID " + projectId + "）不存在或已被删除；请在顶栏重新选择有效项目后再进入「环境配置」";
    }

    public static String deployVersionBlank() {
        return "「部署版本」不能为空；请在「记录环境部署」弹窗的「部署版本」输入框填写版本号（如 1.4.2）后保存";
    }

    public static String deployVersionTooLong(String version) {
        return "部署版本不能超过 50 字（当前 " + version.trim().length()
                + " 字）；请在「记录环境部署」弹窗把「部署版本」缩短后保存";
    }

    public static String nameInvalid(String name) {
        String echo = (name == null || name.isBlank()) ? "为空" : name.trim();
        if (echo.length() > 20) {
            echo = echo.substring(0, 20) + "…";
        }
        return "「环境名称」要求 1~50 字（当前输入「" + echo + "」）；请在「新增环境」/「编辑环境」弹窗重新填写后保存";
    }

    public static String typeInvalid(int envType) {
        return "环境类型取值 " + envType + " 不支持；「环境类型」仅可选：开发环境、测试环境、预发环境、生产环境，请在「新增环境」弹窗下拉中重新选择";
    }

    public static String prodBaseUrlMissing() {
        return "生产环境必须填写「访问地址」，用于执行前生产域名黑名单校验；请在「新增环境」/「编辑环境」弹窗填写「访问地址」后保存";
    }

    public static String typeNotUnique(int envType) {
        return "该项目已存在" + envTypeLabel(envType) + "；每个项目每种「环境类型」只能配一套（不同项目可配置不同环境集合），请到「环境配置」页签「编辑环境」该行，或先删除该行再「新增环境」";
    }
}
