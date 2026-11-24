package com.sixbean.testpike.core.cluster.domain.component;

import java.util.Set;

/**
 * Single-source refusal wording for the release-manifest registration and
 * compatibility pre-check doors under the R63 smart-guidance contract: every
 * sentence echoes the offending submitted value, quotes the on-screen repair
 * entry in 「」 and keeps the legacy capability clause contiguous so existing
 * assertions hold.
 *
 * <p>Quoted literals must match the shipped frontend wording (upgrade-center
 * page hosts the manifest register/import form and the compatibility pre-check
 * card; system settings hosts the download-host whitelist key). Keep them in
 * sync when the screens change.
 */
public final class ReleaseManifestRefusals {

    /** Repair entry quoted for manifest registration and pre-check forms. */
    public static final String UPGRADE_ENTRY = "「升级中心」页";

    private ReleaseManifestRefusals() {
    }

    public static String versionMissing(String submitted) {
        String echo = submitted == null || submitted.isBlank() ? "空白" : "\"" + submitted + "\"";
        return "发布清单登记必须提供版本号（version），本次提交=" + echo
                + "；请到" + UPGRADE_ENTRY + "的发布清单登记/导入处填写版本号（如 1.4.0）后重登";
    }

    public static String versionTooLong(int length) {
        return "版本号超长（上限 60 字符）——本次提交 " + length + " 字符；"
                + "请到" + UPGRADE_ENTRY + "的发布清单登记表单缩短版本号（如 1.4.0）后重登";
    }

    public static String compatRangeInvalid(String min, String max) {
        return "执行器协议兼容区间不合法：下界 " + min + " 大于上界 " + max
                + "——请核对发版件兼容声明后重登；表单字段为" + UPGRADE_ENTRY
                + "登记处的 executor_version_min / executor_version_max";
    }

    public static String sourceInvalid(String submitted) {
        return "发布清单登记来源必须是 manual / import 之一（当前：" + submitted + "）；"
                + "请在" + UPGRADE_ENTRY + "的发布清单登记处改选 manual（手工登记）或 import（发布件导入）";
    }

    public static String suggestedPathTooLong(int length) {
        return "建议升级路径超长（上限 500 字符）——本次提交 " + length + " 字符；"
                + "请在" + UPGRADE_ENTRY + "的发布清单登记表单精简 suggested_path 后重登";
    }

    public static String hypotheticalRangeInvalid(String min, String max) {
        return "假想兼容区间不合法：下界 " + min + " 大于上界 " + max
                + "——请核对候选发版件的兼容声明后重填；该预检入口在" + UPGRADE_ENTRY
                + "的兼容性体检卡（候选区间输入框）";
    }

    public static String jsonParseFailed(String fieldLabel, String parseError) {
        return fieldLabel + "必须是合法 JSON 数组（解析失败：" + parseError + "）；"
                + "请在" + UPGRADE_ENTRY + "的发布清单登记处按示例修正「" + fieldLabel + "」输入框内容";
    }

    public static String jsonNotArray(String fieldLabel) {
        return fieldLabel + "必须是 JSON 数组（如 [\"...\"]），当前不是数组形态；"
                + "请在" + UPGRADE_ENTRY + "的发布清单登记处把「" + fieldLabel
                + "」改为以 [ 开头、] 结尾的数组后重登";
    }

    public static String artifactsParseFailed(String parseError) {
        return "下载工件清单必须是合法 JSON 数组（解析失败：" + parseError + "）；"
                + "请在" + UPGRADE_ENTRY + "的发布清单登记处修正工件 JSON 后重登";
    }

    public static String artifactsNotArray() {
        return "下载工件清单必须是 JSON 数组（[{component_family,url,sha256,size}]），当前不是数组形态；"
                + "请在" + UPGRADE_ENTRY + "的发布清单登记处按该字段示例改为数组形态后重登";
    }

    public static String pinnedWithoutArtifact(Set<String> families) {
        return "部件 " + String.join("、", families)
                + " 声明了钉版但未提供下载件——补齐工件后重登，或去掉钉版改为仅宣告；"
                + "工件在" + UPGRADE_ENTRY + "发布清单登记的下载工件清单（artifacts）中逐族补填";
    }

    public static String hostNotAllowed(String family, String allowedHostsKey, String url) {
        return "工件 " + family + " 的下载域不在白名单内（当前键 " + allowedHostsKey
                + "，镜像源域自动并入）——禁止跟随白名单外 URL 防 SSRF/供应链替换：" + url
                + "；请改用白名单内镜像域重登，确需新增下载域时在「系统设置」维护 " + allowedHostsKey;
    }
}
