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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verified-locator ledger sync (WE batch S3, , W2): when an exploration
 * step acts on an element successfully, the locator it just proved on the live
 * page must land in the ledger instead of dying with the session. This is the
 * success mirror of {@link ExploreAnchorReconcile} (mismatch mirror): every
 * promotion runs through the single-source doors — the lifecycle transition
 * table and WebElementService.changeState/updateAnchors/create — never a raw
 * write. Candidate and stale rows hit by the verified locator reopen as
 * confirmed; an unseen locator is created and immediately promoted; retired
 * rows are terminal and never resurrect; already-confirmed rows replay as a
 * no-op (idempotent, revision untouched).
 * Real dev database, no mocks. Cleanup deletes only rows created here.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class ExploreVerifiedAnchorUpsertTest {

    @Autowired
    private WebElementService webElementService;
    @Autowired
    private ExploreVerifiedAnchorUpsert upsert;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> fixtureProjectIds = new ArrayList<>();

    private long createProject() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        return ProjectFixtureRig.create(jdbcTemplate, projectService, 1L,
                fixtureProjectIds, "验证同步账本项目" + code, code);
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

    private WebElementView candidate(long projectId, String name, String pageUrl,
                                     List<WebElementAnchorInput> anchors) {
        return webElementService.create(new WebElementInput(projectId, name, pageUrl, anchors));
    }

    @Test
    void verifiedAnchorOnCandidateRowPromotesThroughStateDoor() {
        long projectId = createProject();
        String page = "http://t654u.local/login";
        WebElementView row = candidate(projectId, "登录按钮", page,
                List.of(new WebElementAnchorInput("testid", "login-btn", 0.9, null)));
        int eventsBefore = countEvents(row.id());

        ExploreVerifiedAnchorUpsert.Result result = upsert.upsert(projectId, page,
                new ExploreVerifiedAnchorUpsert.Verified("testid", "login-btn"), null);

        assertEquals(ExploreVerifiedAnchorUpsert.Action.PROMOTED, result.action());
        assertEquals(row.id(), result.elementId());
        WebElementView after = webElementService.findById(row.id());
        assertEquals(1, after.state());
        assertTrue(after.revision() > row.revision());
        assertEquals(eventsBefore + 1, countEvents(row.id()));
    }

    @Test
    void verifiedAnchorOnStaleRowReopensItAsConfirmed() {
        long projectId = createProject();
        String page = "http://t654u.local/stale";
        WebElementView created = webElementService.create(new WebElementInput(projectId,
                "搜索框", page, List.of(new WebElementAnchorInput("placeholder", "搜索", 0.8, null))));
        webElementService.changeState(created.id(), 1, created.revision());
        WebElementView stale = webElementService.findById(created.id());
        webElementService.changeState(stale.id(), 2, stale.revision());

        ExploreVerifiedAnchorUpsert.Result result = upsert.upsert(projectId, page,
                new ExploreVerifiedAnchorUpsert.Verified("placeholder", "搜索"), null);

        assertEquals(ExploreVerifiedAnchorUpsert.Action.PROMOTED, result.action());
        assertEquals(1, webElementService.findById(created.id()).state());
    }

    @Test
    void alreadyConfirmedRowReplaysWithoutAnyWrite() {
        long projectId = createProject();
        String page = "http://t654u.local/idem";
        WebElementView created = webElementService.create(new WebElementInput(projectId,
                "提交按钮", page, List.of(new WebElementAnchorInput("testid", "submit-btn", 0.9, null))));
        WebElementView confirmed = webElementService.changeState(created.id(), 1, created.revision());
        int eventsBefore = countEvents(confirmed.id());

        ExploreVerifiedAnchorUpsert.Result result = upsert.upsert(projectId, page,
                new ExploreVerifiedAnchorUpsert.Verified("testid", "submit-btn"), null);

        assertEquals(ExploreVerifiedAnchorUpsert.Action.ALREADY_CONFIRMED, result.action());
        WebElementView after = webElementService.findById(confirmed.id());
        assertEquals(confirmed.revision(), after.revision());
        assertEquals(eventsBefore, countEvents(confirmed.id()));
    }

    @Test
    void retiredRowStaysTerminalAndIsNeverResurrected() {
        long projectId = createProject();
        String page = "http://t654u.local/retired";
        WebElementView created = webElementService.create(new WebElementInput(projectId,
                "旧版入口", page, List.of(new WebElementAnchorInput("testid", "legacy-btn", 0.9, null))));
        webElementService.changeState(created.id(), 1, created.revision());
        WebElementView confirmed = webElementService.findById(created.id());
        webElementService.changeState(confirmed.id(), 3, confirmed.revision());

        ExploreVerifiedAnchorUpsert.Result result = upsert.upsert(projectId, page,
                new ExploreVerifiedAnchorUpsert.Verified("testid", "legacy-btn"), null);

        assertEquals(ExploreVerifiedAnchorUpsert.Action.RETIRED_SKIPPED, result.action());
        assertEquals(3, webElementService.findById(created.id()).state());
    }

    @Test
    void unseenVerifiedLocatorEntersLedgerAlreadyConfirmedUnderGivenLabel() {
        long projectId = createProject();
        String page = "http://t654u.local/new";

        ExploreVerifiedAnchorUpsert.Result result = upsert.upsert(projectId, page,
                new ExploreVerifiedAnchorUpsert.Verified("css", "#save"), "保存按钮");

        assertEquals(ExploreVerifiedAnchorUpsert.Action.CREATED_CONFIRMED, result.action());
        WebElementView row = webElementService.findById(result.elementId());
        assertEquals("保存按钮", row.name());
        assertEquals(1, row.state());
        assertEquals(1, row.anchors().size());
        assertEquals("css", row.anchors().get(0).type());
        assertEquals("#save", row.anchors().get(0).value());
    }

    @Test
    void sameNameRowGainsTheVerifiedAnchorBesideItsOldOne() {
        long projectId = createProject();
        String page = "http://t654u.local/extend";
        WebElementView row = candidate(projectId, "登录按钮", page,
                List.of(new WebElementAnchorInput("css", "[data-test=\"login-btn\"]", 0.5, null)));

        ExploreVerifiedAnchorUpsert.Result result = upsert.upsert(projectId, page,
                new ExploreVerifiedAnchorUpsert.Verified("testid", "login-btn"), "登录按钮");

        assertEquals(ExploreVerifiedAnchorUpsert.Action.ANCHORS_EXTENDED, result.action());
        WebElementView after = webElementService.findById(row.id());
        assertEquals(row.id(), result.elementId());
        assertEquals(2, after.anchors().size());
        assertEquals(1, after.state());
    }

    @Test
    void stepWireExtractionKeepsLedgerLocatorDialectsAndDropsNonVerifiedSteps() {
        String action = """
                {"name":"click","arguments":{"url":"http://t654u.local/p",
                 "target":{"testid":"ok-btn","role":"button","css":"#also"}}}""";

        ExploreVerifiedAnchorUpsert.StepVerification ok =
                ExploreVerifiedAnchorUpsert.forStep(action, "{\"ok\":true,\"observation\":\"done\"}");
        assertNull(ExploreVerifiedAnchorUpsert.forStep(action, "{\"ok\":false}"));
        assertNull(ExploreVerifiedAnchorUpsert.forStep(
                "{\"name\":\"goto\",\"arguments\":{\"url\":\"http://a\"}}", "{\"ok\":true}"));
        assertNull(ExploreVerifiedAnchorUpsert.forStep(null, null));

        assertTrue(ok != null);
        assertEquals("http://t654u.local/p", ok.pageUrl());
        // Ledger dialects all survive (document order); the free-form "role"
        // key is not a locator and never fabricates an anchor.
        assertEquals(List.of(new ExploreVerifiedAnchorUpsert.Verified("testid", "ok-btn"),
                        new ExploreVerifiedAnchorUpsert.Verified("css", "#also")),
                ok.verified());
    }

    @Test
    void blankOrUnknownLocatorsAreIgnoredNotFabricated() {
        long projectId = createProject();
        ExploreVerifiedAnchorUpsert.Result blank = upsert.upsert(projectId,
                "http://t654u.local/blank",
                new ExploreVerifiedAnchorUpsert.Verified("testid", "  "), null);
        assertEquals(ExploreVerifiedAnchorUpsert.Action.IGNORED, blank.action());
        assertNull(blank.elementId());
        assertTrue(webElementService.listByProject(projectId, null).isEmpty());
    }
}
