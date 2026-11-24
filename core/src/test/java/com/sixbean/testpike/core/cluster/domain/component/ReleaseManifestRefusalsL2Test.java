package com.sixbean.testpike.core.cluster.domain.component;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R63 contract for the release-manifest registration doors: every refusal must
 * echo the submitted value (or its measurable shape), quote the on-screen
 * repair entry, and keep the legacy capability clause contiguous so existing
 * assertions stay green.
 */
class ReleaseManifestRefusalsL2Test {

    /** Legacy clauses asserted contiguous by bridge/L3 tests and this ledger. */
    private static void assertKeepsLegacy(String message, String legacy) {
        assertTrue(message.contains(legacy),
                "legacy clause must stay contiguous. actual: " + message);
    }

    private static void assertHasRepairEntry(String message) {
        assertTrue(message.contains("「") || message.contains("TESTPIKE_") || message.contains("tp.")
                        || message.contains("cluster.release"),
                "message must name an on-screen entry or a config key. actual: " + message);
    }

    @Test
    void versionMissingEchoesSubmittedValueAndNamesEntry() {
        String msg = ReleaseManifestRefusals.versionMissing("   ");
        assertKeepsLegacy(msg, "发布清单登记必须提供版本号（version）");
        assertTrue(msg.contains("本次提交=空白"), "must echo the submitted blank. actual: " + msg);
        assertHasRepairEntry(msg);
    }

    @Test
    void versionTooLongEchoesActualLength() {
        String msg = ReleaseManifestRefusals.versionTooLong(87);
        assertKeepsLegacy(msg, "版本号超长（上限 60 字符）");
        assertTrue(msg.contains("87"), "must echo the actual length. actual: " + msg);
        assertHasRepairEntry(msg);
    }

    @Test
    void compatRangeInvalidAlreadyEchoesBoundsAndNowNamesEntry() {
        String msg = ReleaseManifestRefusals.compatRangeInvalid("2.5.0", "2.1.0");
        assertKeepsLegacy(msg, "执行器协议兼容区间不合法：下界 2.5.0 大于上界 2.1.0");
        assertHasRepairEntry(msg);
    }

    @Test
    void sourceInvalidEchoesSubmittedSourceAndListsAllowedValues() {
        String msg = ReleaseManifestRefusals.sourceInvalid("auto");
        assertKeepsLegacy(msg, "发布清单登记来源必须是 manual / import 之一（当前：auto）");
        assertHasRepairEntry(msg);
    }

    @Test
    void suggestedPathTooLongEchoesActualLength() {
        String msg = ReleaseManifestRefusals.suggestedPathTooLong(913);
        assertKeepsLegacy(msg, "建议升级路径超长（上限 500 字符）");
        assertTrue(msg.contains("913"), "must echo the actual length. actual: " + msg);
        assertHasRepairEntry(msg);
    }

    @Test
    void hypotheticalRangeInvalidEchoesBoundsAndNamesPreCheckEntry() {
        String msg = ReleaseManifestRefusals.hypotheticalRangeInvalid("3.0.0", "1.0.0");
        assertKeepsLegacy(msg, "假想兼容区间不合法：下界 3.0.0 大于上界 1.0.0");
        assertHasRepairEntry(msg);
    }

    @Test
    void jsonParseFailureNamesFieldAndEntry() {
        String msg = ReleaseManifestRefusals.jsonParseFailed("部件矩阵", "Unexpected character");
        assertKeepsLegacy(msg, "部件矩阵必须是合法 JSON 数组（解析失败：Unexpected character）");
        assertHasRepairEntry(msg);
    }

    @Test
    void jsonNotArrayKeepsShapeExampleAndNamesEntry() {
        String msg = ReleaseManifestRefusals.jsonNotArray("破坏性变更清单");
        assertKeepsLegacy(msg, "破坏性变更清单必须是 JSON 数组");
        assertHasRepairEntry(msg);
    }

    @Test
    void artifactsParseFailureKeepsClauseAndNamesEntry() {
        String msg = ReleaseManifestRefusals.artifactsParseFailed("no content");
        assertKeepsLegacy(msg, "下载工件清单必须是合法 JSON 数组（解析失败：no content）");
        assertHasRepairEntry(msg);
    }

    @Test
    void artifactsNotArrayKeepsShapeExampleAndNamesEntry() {
        String msg = ReleaseManifestRefusals.artifactsNotArray();
        assertKeepsLegacy(msg, "下载工件清单必须是 JSON 数组（[{component_family,url,sha256,size}]）");
        assertHasRepairEntry(msg);
    }

    @Test
    void pinnedFamilyWithoutArtifactEchoesFamiliesAndNamesEntry() {
        Set<String> missing = new LinkedHashSet<>(Set.of("executor"));
        String msg = ReleaseManifestRefusals.pinnedWithoutArtifact(missing);
        assertKeepsLegacy(msg, "部件 executor 声明了钉版但未提供下载件");
        assertHasRepairEntry(msg);
    }

    @Test
    void hostNotAllowedKeepsKeyAndEchoesFamilyUrl() {
        String msg = ReleaseManifestRefusals.hostNotAllowed("executor",
                "testpike.cluster.artifact-allowed-hosts", "http://evil.example/a.tgz");
        assertKeepsLegacy(msg, "工件 executor 的下载域不在白名单内（当前键 testpike.cluster.artifact-allowed-hosts");
        assertTrue(msg.contains("http://evil.example/a.tgz"), "must echo the offending url. actual: " + msg);
        assertHasRepairEntry(msg);
    }
}
