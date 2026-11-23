package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-page survey outcome ingestion (WE batch S4, segment P2): when a survey
 * session journals the G4 candidate pool, every observed element must enter
 * the page element ledger as a CANDIDATE row through the single-source doors
 * (create/changeState/updateAnchors/updateVariants) — never a raw write, and
 * never a silent promotion (human approval stays the only door into
 * confirmed). Replay of the same pool writes nothing; confirmed and stale
 * rows are kept as-is; retired rows never resurrect; per-viewport sweeps
 * merge into ONE row whose variants carry the tier anchor sets.
 * Real dev database, no mocks. Cleanup deletes only rows created here.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class WebElementSurveyCollectorTest {

    @Autowired
    private WebElementService webElementService;
    @Autowired
    private WebElementSurveyCollector collector;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> fixtureProjectIds = new ArrayList<>();

    private long createProject() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        return ProjectFixtureRig.create(jdbcTemplate, projectService, 1L,
                fixtureProjectIds, "整页测绘入账项目" + code, code);
    }

    @AfterEach
    void cleanUp() {
        for (Long projectId : fixtureProjectIds) {
            jdbcTemplate.update(
                    "DELETE FROM tp_ai_task_event WHERE job_kind = 'element-ledger'"
                            + " AND job_ref IN (SELECT id::text FROM tp_web_element WHERE project_id = ?)",
                    projectId);
            jdbcTemplate.update("DELETE FROM tp_web_element WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        }
        fixtureProjectIds.clear();
    }

    private int countEvents(long elementId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_task_event WHERE job_kind = 'element-ledger'"
                        + " AND job_ref = ?",
                Integer.class, String.valueOf(elementId));
        return n == null ? 0 : n;
    }

    private static String poolOf(String body) {
        return "{\"candidates\":[" + body + "],\"truncated\":false}";
    }

    private WebElementSurveyCollector.Evidence evidence() {
        return new WebElementSurveyCollector.Evidence(4711L, "shot-key-1", "dom-key-1");
    }

    private static final String THREE_ITEM_POOL = poolOf(
            "{\"tag\":\"button\",\"role\":\"button\",\"name\":\"登录按钮\",\"text\":\"登录\","
                    + "\"target\":{\"css\":\"#login\",\"text\":\"登录\",\"testid\":\"login-btn\"}},"
                    + "{\"tag\":\"input\",\"name\":\"用户名输入\",\"target\":"
                    + "{\"css\":\"input[name=user]\",\"placeholder\":\"用户名\"}},"
                    + "{\"tag\":\"a\",\"text\":\"帮助中心\",\"target\":{\"css\":\"a.help\"}}");

    @Test
    void surveyPoolCreatesCandidateRowsThroughDoors() {
        long projectId = createProject();
        String page = "http://t655p2.local/login";

        List<WebElementSurveyCollector.ItemOutcome> outcomes =
                collector.survey(projectId, page, THREE_ITEM_POOL, evidence());

        assertEquals(3, outcomes.size());
        assertEquals(3, outcomes.stream()
                .filter(o -> o.action() == WebElementSurveyCollector.Action.CREATED).count());
        // pageKey is folded from the URL, so segment by project and keep our page rows
        List<WebElementView> rows = webElementService.listByProject(projectId, null);
        assertEquals(3, rows.size());
        for (WebElementView row : rows) {
            assertEquals(0, row.state(), "survey rows enter as candidates, never confirmed");
            assertTrue(row.anchors().size() >= 1);
        }
        WebElementView login = rows.stream()
                .filter(r -> "登录按钮".equals(r.name())).findFirst().orElseThrow();
        assertTrue(login.anchors().stream()
                .anyMatch(a -> "testid".equals(a.type()) && "login-btn".equals(a.value())));
        assertTrue(login.anchors().stream().allMatch(a -> a.note() != null
                        && a.note().contains("sessionId=4711")),
                "anchor note carries the survey session evidence keys");
    }

    @Test
    void replayOfSamePoolWritesNothing() {
        long projectId = createProject();
        String page = "http://t655p2.local/idem";

        List<WebElementSurveyCollector.ItemOutcome> first =
                collector.survey(projectId, page, THREE_ITEM_POOL, evidence());
        assertEquals(3, first.size());
        List<WebElementView> afterFirst = webElementService.listByProject(projectId, null);
        int eventsFirst = afterFirst.stream().mapToInt(r -> countEvents(r.id())).sum();

        List<WebElementSurveyCollector.ItemOutcome> second =
                collector.survey(projectId, page, THREE_ITEM_POOL, evidence());

        assertTrue(second.stream()
                        .allMatch(o -> o.action() == WebElementSurveyCollector.Action.REPLAYED),
                "idempotent replay: same pool writes zero ledger rows, got " + second);
        List<WebElementView> afterSecond = webElementService.listByProject(projectId, null);
        assertEquals(afterFirst.size(), afterSecond.size());
        int eventsSecond = afterSecond.stream().mapToInt(r -> countEvents(r.id())).sum();
        assertEquals(eventsFirst, eventsSecond, "replay must not add audit events");
        for (WebElementView row : afterSecond) {
            WebElementView before = afterFirst.stream()
                    .filter(r -> r.id().equals(row.id())).findFirst().orElseThrow();
            assertEquals(before.revision(), row.revision());
        }
    }

    @Test
    void crossViewportSweepMergesIntoOneRowCarryingVariants() {
        long projectId = createProject();
        String page = "http://t655p2.local/resp";
        String pool = "{\"candidates\":[{"
                + "\"tag\":\"button\",\"name\":\"提交按钮\",\"target\":{\"css\":\"#submit\"}}],"
                + "\"variants\":["
                + "{\"viewport\":\"mobile\",\"candidates\":[{\"tag\":\"button\",\"name\":\"提交按钮\","
                + "\"target\":{\"css\":\"#submit.m\"}}]},"
                + "{\"viewport\":\"desktop\",\"candidates\":[{\"tag\":\"button\",\"name\":\"提交按钮\","
                + "\"target\":{\"css\":\"#submit.d\"}}]}],"
                + "\"truncated\":false}";

        List<WebElementSurveyCollector.ItemOutcome> outcomes =
                collector.survey(projectId, page, pool, evidence());

        assertEquals(1, outcomes.size());
        List<WebElementView> rows = webElementService.listByProject(projectId, null);
        assertEquals(1, rows.size(), "same element across tiers stays ONE ledger row");
        List<WebElementView> mobileRows = rows;
        assertEquals(2, mobileRows.get(0).variants().size());
        assertTrue(mobileRows.get(0).variants().stream()
                .anyMatch(v -> "mobile".equals(v.viewport())
                        && v.anchors().stream().anyMatch(a -> "#submit.m".equals(a.value()))));
        assertTrue(mobileRows.get(0).variants().stream()
                .anyMatch(v -> "desktop".equals(v.viewport())
                        && v.anchors().stream().anyMatch(a -> "#submit.d".equals(a.value()))));
        // Replay of the identical pool with the same tiers must still be a no-op.
        List<WebElementSurveyCollector.ItemOutcome> replay =
                collector.survey(projectId, page, pool, evidence());
        assertEquals(WebElementSurveyCollector.Action.REPLAYED, replay.get(0).action());
    }

    @Test
    void confirmedRowHitBySurveyIsKeptNotDemotedNorRewritten() {
        long projectId = createProject();
        String page = "http://t655p2.local/keep";
        WebElementView confirmed = webElementService.create(new WebElementInput(projectId,
                "搜索按钮", page, List.of(new WebElementAnchorInput("css", "#search", 0.8, null))));
        WebElementView promoted = webElementService.changeState(confirmed.id(), 1,
                confirmed.revision());
        int eventsBefore = countEvents(promoted.id());
        String pool = poolOf("{\"tag\":\"button\",\"name\":\"搜索按钮\",\"target\":"
                + "{\"css\":\"#search\",\"testid\":\"search-x\"}}");

        List<WebElementSurveyCollector.ItemOutcome> outcomes =
                collector.survey(projectId, page, pool, evidence());

        assertEquals(1, outcomes.size());
        assertEquals(WebElementSurveyCollector.Action.STATE_KEPT, outcomes.get(0).action());
        WebElementView after = webElementService.findById(promoted.id());
        assertEquals(1, after.state(), "survey never demotes a confirmed asset");
        assertEquals(promoted.revision(), after.revision(),
                "observed extra anchors never rewrite a confirmed row without approval");
        assertEquals(eventsBefore, countEvents(after.id()));
    }

    @Test
    void retiredRowIsNeverResurrectedAndMalformedPoolFailsSoft() {
        long projectId = createProject();
        String page = "http://t655p2.local/retire";
        WebElementView created = webElementService.create(new WebElementInput(projectId,
                "旧版入口", page, List.of(new WebElementAnchorInput("css", ".legacy", 0.6, null))));
        WebElementView retired = webElementService.changeState(created.id(), 3,
                created.revision());

        List<WebElementSurveyCollector.ItemOutcome> outcomes = collector.survey(projectId, page,
                poolOf("{\"tag\":\"a\",\"name\":\"旧版入口\",\"target\":{\"css\":\".legacy\"}}"),
                evidence());
        assertEquals(1, outcomes.size());
        assertEquals(WebElementSurveyCollector.Action.RETIRED_SKIPPED, outcomes.get(0).action());
        assertEquals(retired.revision(), webElementService.findById(created.id()).revision());

        assertTrue(collector.survey(projectId, "http://t655p2.local/bad",
                "not-a-json", evidence()).isEmpty());
        assertTrue(collector.survey(projectId, "http://t655p2.local/bad2",
                "{\"nope\":1}", evidence()).isEmpty());
    }

    @Test
    void surveyGoalMarkerIsSingleSourced() {
        assertTrue(WebElementSurveyCollector.isSurveyGoal("整页测绘：http://x 全量采集候选"));
        assertFalse(WebElementSurveyCollector.isSurveyGoal("自由探索登录流程"));
        assertFalse(WebElementSurveyCollector.isSurveyGoal(null));
        assertFalse(WebElementSurveyCollector.isSurveyGoal("  "));
    }
}
