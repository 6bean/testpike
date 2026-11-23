package com.sixbean.testpike.admin.judge.api.controller;

import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import com.sixbean.testpike.common.rbac.PermissionDomain;
import com.sixbean.testpike.common.rbac.RoleTemplate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AW batch judge-facade door wiring test .
 * <p>Asserted surface: ①the frozen five-key JUDGE domain family is registered in the
 * catalog with the ruled action families (evaluation-set read = VIEW so every member
 * role sees the page door per the R24-5 no-narrowing rule; evaluation-set writes =
 * CREATE matching the skill/asset authoring precedent, NOT MANAGE, because the D2
 * exit gate addresses testers who must be able to build the set; AI question
 * generation = RUN like the other AI-cost-gate lanes; run dispatch = CREATE;
 * run report read = MANAGE, admin parity with the borrowed key it replaces);
 * ②every judge controller lane carries its ruled key by reflection signature
 * (annotation drift = test fails first);
 * ③M1 crowd equivalence: for every built-in role template, holding the old borrowed
 * key perm:knowledge:mcp-manage and holding perm:judge:run:view are identical —
 * the migration renames the door without moving the population (zero drift, pinned);
 * ④the knowledge-domain G7 key keeps its own meaning (its page lanes are untouched).
 */
class JudgeDomainPermissionWiringTest {

    private static String permOf(Class<?> controller, String methodName) {
        Method m = Arrays.stream(controller.getMethods())
                .filter(c -> c.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "judge lane signature drift (method renamed or removed): " + methodName));
        RequirePermission ann = m.getAnnotation(RequirePermission.class);
        return ann == null ? null : ann.value();
    }

    @Test
    void catalogRegistersTheFrozenFiveKeyJudgeFamily() {
        PermissionCatalog evalsetView = PermissionCatalog.fromKey("perm:judge:evalset:view");
        PermissionCatalog evalsetManage = PermissionCatalog.fromKey("perm:judge:evalset:manage");
        PermissionCatalog qagenRun = PermissionCatalog.fromKey("perm:judge:qagen:run");
        PermissionCatalog runCreate = PermissionCatalog.fromKey("perm:judge:run:create");
        PermissionCatalog runView = PermissionCatalog.fromKey("perm:judge:run:view");
        for (PermissionCatalog entry : new PermissionCatalog[] {
                evalsetView, evalsetManage, qagenRun, runCreate, runView}) {
            assertNotNull(entry, "judge facade key missing from catalog: expected frozen AW family");
            assertEquals(PermissionDomain.JUDGE, entry.domain(),
                    "domain grouping must be JUDGE for " + entry.key());
        }
        assertEquals(PermActionFamily.VIEW, evalsetView.family(),
                "evaluation-set read door = VIEW family (page visible from developer up)");
        assertEquals(PermActionFamily.CREATE, evalsetManage.family(),
                "evaluation-set authoring = CREATE family (tester/lead/PM write the set)");
        assertEquals(PermActionFamily.RUN, qagenRun.family(),
                "AI question generation = RUN family (AI-cost-gate lane precedent)");
        assertEquals(PermActionFamily.CREATE, runCreate.family(),
                "run dispatch = CREATE family per AW4 ruling");
        assertEquals(PermActionFamily.MANAGE, runView.family(),
                "run report read = MANAGE family: M1 pins the crowd to exactly the"
                        + " old borrowed-key holders (admin parity, zero drift)");
        assertEquals("judge", PermissionDomain.JUDGE.token(),
                "key domain segment must equal the domain token (single source)");
        long judgeKeys = Arrays.stream(PermissionCatalog.values())
                .filter(p -> p.domain() == PermissionDomain.JUDGE).count();
        // Frozen JUDGE-domain set: the five AW facade keys. A sixth key requires an
        // explicit ruling, not a silent catalog append.
        assertEquals(5, judgeKeys, "JUDGE domain carries exactly the five frozen facade keys");
    }

    @Test
    void judgeControllerLanesCarryTheirRuledKeys() {
        assertEquals("perm:judge:qagen:run", permOf(EvalSetController.class, "previewQuestions"),
                "qa-preview lane must require perm:judge:qagen:run");
        assertEquals("perm:judge:qagen:run", permOf(EvalSetController.class, "qaGenJob"),
                "generation job status lane rides the same door");
        assertEquals("perm:judge:qagen:run", permOf(EvalSetController.class, "qaGenEvents"),
                "generation event lane rides the same door");
        assertEquals("perm:judge:evalset:manage", permOf(EvalSetController.class, "publish"),
                "publish-version lane must require perm:judge:evalset:manage");
        for (String lane : new String[] {"sampleCards", "diffRuns", "costCard",
                "scorecard", "scorecardTrend", "scorecardSessionCards"}) {
            assertEquals("perm:judge:run:view", permOf(JudgeReportController.class, lane),
                    "report read lane " + lane + " must require perm:judge:run:view (M1)");
        }
    }

    @Test
    void m1MigrationHoldsZeroCrowdDriftOnEveryBuiltInRole() {
        for (RoleTemplate template : RoleTemplate.values()) {
            Set<String> baseline = template.baselineKeys();
            boolean hadOld = baseline.contains("perm:knowledge:mcp-manage");
            boolean hasNew = baseline.contains("perm:judge:run:view");
            assertEquals(hadOld, hasNew,
                    "role " + template.code() + " must see the report page exactly as before"
                            + " the key swap (M1 zero-drift ruling)");
        }
        // Positive anchor so the equivalence assertion above cannot pass by both sides
        // being empty (silent no-op guard, R43-3): admin holds both keys, developer holds
        // neither.
        assertTrue(RoleTemplate.ADMIN.baselineKeys().contains("perm:judge:run:view"),
                "admin must hold the report door after migration");
        assertTrue(RoleTemplate.ADMIN.baselineKeys().contains("perm:knowledge:mcp-manage"),
                "admin keeps its own knowledge-domain door (G7 untouched)");
        assertTrue(!RoleTemplate.DEVELOPER.baselineKeys().contains("perm:judge:run:view"),
                "developer must not gain the report door through the rename");
    }

    @Test
    void knowledgeG7KeyKeepsItsOwnDomainAndFamily() {
        PermissionCatalog mcpManage = PermissionCatalog.fromKey("perm:knowledge:mcp-manage");
        assertNotNull(mcpManage);
        assertEquals(PermissionDomain.KNOWLEDGE, mcpManage.domain(),
                "the knowledge door stays a knowledge key (G7 keeps its meaning)");
        assertEquals(PermActionFamily.MANAGE, mcpManage.family());
    }
}
