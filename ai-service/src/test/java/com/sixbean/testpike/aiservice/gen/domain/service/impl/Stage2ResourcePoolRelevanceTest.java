package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Resource-pool relevance selection : the stage-2 prompt must
 * carry the MOST RELEVANT pool entries instead of the first N by storage order
 * — the miss/wrong-reference risk of a truncating name list (ruling
 * <p>Contract under test: data factories and API collections rank by
 * (case-text token match + usage boost), ties break on the newest stored time,
 * an all-zero-score pool keeps its original order (never gamble on relevance),
 * environments stay fully exempt from the cap (a missing base URL invalidates
 * every step), and the named-entry cap rides the hot key
 * {@code ai.gen.resource-pool.segment-cap} (default 30, window 10-100, any
 * other value fails closed to the default). A blank case text keeps the exact
 * legacy prompt bytes.
 * <p>L2 posture: infrastructure mocks via {@link GenWiringHarness} single
 * source; every judgement function stays the real one.
 */
class Stage2ResourcePoolRelevanceTest {

    private static final long PROJECT_ID = 3L;

    private final CoreGateway coreGateway = GenWiringHarness.mockCoreGateway();

    private static List<Map<String, Object>> factories(int n, String olderMinute) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            rows.add(Map.of(
                    "name", String.format("df%02d", i),
                    "createdAt", String.format("2026-01-%s 10:%02d:00", olderMinute, i)));
        }
        return rows;
    }

    private static List<Map<String, Object>> collections(int n) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            rows.add(Map.of("name", String.format("api%02d", i),
                    "tags", i == n ? "pay,refund" : "", "requestCount", 0L));
        }
        return rows;
    }

    private void pool(List<CoreGateway.EnvBrief> envs,
                      List<Map<String, Object>> dfs,
                      List<Map<String, Object>> apis,
                      String capConfig) {
        when(coreGateway.listProjectEnvs(PROJECT_ID)).thenReturn(envs);
        when(coreGateway.listProjectDataFactories(eq(PROJECT_ID), anyLong())).thenReturn(dfs);
        when(coreGateway.listProjectApiCollections(eq(PROJECT_ID), anyLong())).thenReturn(apis);
        when(coreGateway.getSysConfig("ai.gen.resource-pool.segment-cap"))
                .thenReturn(capConfig);
    }

    private static int namedLineCount(String segment, String label) {
        int c = 0, i = 0;
        while ((i = segment.indexOf(label, i)) >= 0) {
            c++;
            i += label.length();
        }
        return c;
    }

    @Test
    void relevantEntriesEnterSegmentEvenWhenStoredBeyondCap() {
        pool(List.of(), factories(35, "05"), collections(35), null);
        String segment = AiGenServiceImpl.projectResourceSegment(
                coreGateway, PROJECT_ID, "step one df35 then the refund flow");

        assertTrue(segment.contains("df35"),
                "case-named factory beyond storage position 30 must ride the prompt");
        assertTrue(segment.contains("api35"),
                "collection matched through its tags must ride the prompt");
    }

    @Test
    void sameScoreNewerStoredTimeWins() {
        pool(List.of(),
                new ArrayList<>(List.of(
                        Map.of("name", "login-user", "createdAt", "2026-01-02 10:00:00"),
                        Map.of("name", "login-order", "createdAt", "2026-01-09 10:00:00"))),
                List.of(), null);
        String segment = AiGenServiceImpl.projectResourceSegment(
                coreGateway, PROJECT_ID, "prepare login data first");

        assertTrue(segment.indexOf("login-order") < segment.indexOf("login-user"),
                "equal-score rows must order newest stored time first");
    }

    @Test
    void allZeroScoreKeepsOriginalOrderAndLegacyBytes() {
        pool(List.of(), factories(35, "05"), collections(35), null);
        String ranked = AiGenServiceImpl.projectResourceSegment(
                coreGateway, PROJECT_ID, "与资源池毫无关联的步骤");
        String legacy = AiGenServiceImpl.projectResourceSegment(coreGateway, PROJECT_ID);

        assertEquals(legacy, ranked, "all-zero pool must fall back to original order");
        assertTrue(ranked.indexOf("df01") < ranked.indexOf("df02"),
                "fallback keeps the storage order");
    }

    @Test
    void environmentsStayFullyExemptBeyondCap() {
        List<CoreGateway.EnvBrief> envs = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            envs.add(new CoreGateway.EnvBrief((long) i, 1, "https://env" + i + ".test", "env"));
        }
        pool(envs, factories(35, "05"), List.of(), null);
        String segment = AiGenServiceImpl.projectResourceSegment(
                coreGateway, PROJECT_ID, "step df35 first");

        assertEquals(5, namedLineCount(segment, "环境（envType=1）："),
                "every environment entry rides the prompt regardless of the named cap");
        assertTrue(segment.contains("（其余 5 项未列出）"),
                "tail note counts factory/collection rows only");
    }

    @Test
    void capKeyHotValueDrivesNamedLines() {
        pool(List.of(), factories(35, "05"), List.of(), "12");
        String segment = AiGenServiceImpl.projectResourceSegment(
                coreGateway, PROJECT_ID, "df35 相关步骤");

        assertEquals(12, namedLineCount(segment, "数据工厂："),
                "hot key value 12 must cap the named factory rows at 12");
        assertTrue(segment.contains("（其余 23 项未列出）"), "tail note follows the hot cap");
    }

    @Test
    void capKeyFailsClosedOutsideWindow() {
        assertEquals(30, AiGenServiceImpl.resourceSegmentCap(null));
        assertEquals(30, AiGenServiceImpl.resourceSegmentCap(""));
        assertEquals(30, AiGenServiceImpl.resourceSegmentCap("abc"));
        assertEquals(30, AiGenServiceImpl.resourceSegmentCap("1"),
                "below-window value must not silently shrink the prompt");
        assertEquals(30, AiGenServiceImpl.resourceSegmentCap("101"),
                "above-window value must not silently grow the prompt");
        assertEquals(10, AiGenServiceImpl.resourceSegmentCap("10"));
        assertEquals(100, AiGenServiceImpl.resourceSegmentCap("100"));
    }
}
