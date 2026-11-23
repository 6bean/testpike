package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Resource-gap declaration contract ( segment c, ruling
 * prompt carries a NON-EMPTY project resource pool, its tail must additionally
 * teach the model the optional output key {@code missingResources[]} — the
 * honest channel for "the resource category I meant to reference is not in the
 * list" (risk state 3: silent under-reference no existing gate can catch).
 * <p>Backward-compat posture (RegenerationFeedback discipline): an empty pool
 * injects NOTHING at all (no dangling contract line on a pool-less prompt),
 * and the guidance rides exactly once per segment. Declaring stays the model's
 * choice; absence keeps the legacy shape (DSL validation is not tightened).
 * <p>L2 posture: infrastructure mocks via {@link GenWiringHarness} single
 * source; every judgement function stays the real one.
 */
class Stage2ResourceGapContractTest {

    private static final long PROJECT_ID = 3L;

    private final CoreGateway coreGateway = GenWiringHarness.mockCoreGateway();

    private void pool(List<CoreGateway.EnvBrief> envs,
                      List<Map<String, Object>> dfs,
                      List<Map<String, Object>> apis) {
        when(coreGateway.listProjectEnvs(PROJECT_ID)).thenReturn(envs);
        when(coreGateway.listProjectDataFactories(eq(PROJECT_ID), anyLong())).thenReturn(dfs);
        when(coreGateway.listProjectApiCollections(eq(PROJECT_ID), anyLong())).thenReturn(apis);
        when(coreGateway.getSysConfig("ai.gen.resource-pool.segment-cap"))
                .thenReturn(null);
    }

    @Test
    void gapGuidanceRidesTailOnceWhenPoolPresent() {
        pool(List.of(),
                List.of(Map.of("name", "df-login", "createdAt", "2026-01-05 10:00:00")),
                List.of());

        String segment = AiGenServiceImpl.projectResourceSegment(
                coreGateway, PROJECT_ID, "登录取数");

        assertTrue(segment.contains("missingResources[]"),
                "a pool-carrying prompt must teach the optional declaration key");
        int first = segment.indexOf("missingResources[]");
        assertEquals(first, segment.lastIndexOf("missingResources[]"),
                "the guidance sentence must ride the tail exactly once");
        assertTrue(first > segment.indexOf("df-login"),
                "the guidance follows the pool lines, never precedes them");
    }

    /**
 * segment c follow-up (live window 2026-11-12, task 2895): the pool tail
 * taught the declaration key only as an OPTION ("may declare… skipping is
 * fine"), and the model answered by inventing an off-list URL instead —
 * zero declaration rows, repair rounds burned on the fake reference. The
 * off-list addresses/names, while the never-block posture keeps its exact
 * phrase: not declaring still never blocks the generation.
 */
    @Test
    void gapGuidanceDirectsDeclarationAndForbidsInvention() {
        pool(List.of(),
                List.of(Map.of("name", "df-sms", "createdAt", "2026-01-05 10:00:00")),
                List.of());

        String segment = AiGenServiceImpl.projectResourceSegment(
                coreGateway, PROJECT_ID, "验证码发送核对");

        assertTrue(segment.contains("必须在输出 JSON"),
                "the declaration must be directed, not merely offered");
        assertTrue(segment.contains("禁止编造清单外的地址或条目名称"),
                "inventing off-list references must be named and forbidden");
        assertTrue(segment.contains("不申报不影响本次生成"),
                "the never-block posture phrase must survive the rewrite");
    }

    /**
 * segment c second follow-up (live window 2026-11-12, tasks 2898/2899):
 * concrete shape — the model again stayed silent and answered with
 * placeholder steps unrelated to the case. The instruction must therefore
 * carry no optional-phrasing contradiction, one worked example of the
 * declaration shape, and an explicit ban on off-topic placeholder steps.
 */
    @Test
    void gapGuidanceCarriesNoContradictionExampleShapeAndPlaceholderBan() {
        pool(List.of(),
                List.of(Map.of("name", "df-sms2", "createdAt", "2026-01-05 10:00:00")),
                List.of());

        String segment = AiGenServiceImpl.projectResourceSegment(
                coreGateway, PROJECT_ID, "验证码发送核对");

        assertTrue(segment.contains("顶层键 missingResources[]"),
                "the directed key must not be labelled optional in the same breath");
        assertTrue(!segment.contains("可选键"),
                "no 可选 contradiction may ride a 必须 directive");
        assertTrue(segment.contains("申报形态示例"),
                "a worked example anchors the exact declaration shape");
        assertTrue(segment.contains("占位步骤"),
                "off-topic placeholder replies must be named and forbidden");
    }

    @Test
    void emptyPoolInjectsNothingNoDanglingGuidance() {
        pool(List.of(), List.of(), List.of());

        String segment = AiGenServiceImpl.projectResourceSegment(
                coreGateway, PROJECT_ID, "任意步骤");

        assertEquals("", segment,
                "empty pool = zero injection, no orphan contract tail line");
    }
}
