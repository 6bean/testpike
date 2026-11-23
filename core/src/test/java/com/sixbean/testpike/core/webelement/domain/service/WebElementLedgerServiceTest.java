package com.sixbean.testpike.core.webelement.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementPageView;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Element ledger service doors (WE batch S0, package 2): creation with the
 * single-source page-key fold (W4), anchor value-domain checks, the lifecycle
 * transition table enforced service-side, and the optimistic lock aligned with
 * the shared 40901 family. Every state transition leaves one tp_ai_task_event
 * row (job_kind=element-ledger) as the audit trail.
 * <p>Real dev database, no mocks. Cleanup deletes only rows created here.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class WebElementLedgerServiceTest {

    private static final int STATE_CANDIDATE = 0;
    private static final int STATE_CONFIRMED = 1;
    private static final int STATE_STALE = 2;
    private static final int STATE_RETIRED = 3;

    @Autowired
    private WebElementService webElementService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> fixtureProjectIds = new ArrayList<>();

    private long createProject() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        return ProjectFixtureRig.create(jdbcTemplate, projectService, 1L,
                fixtureProjectIds, "元素账本项目" + code, code);
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

    private WebElementView createElement(long projectId, String name, String pageUrl,
                                         List<WebElementAnchorInput> anchors) {
        return webElementService.create(new WebElementInput(projectId, name, pageUrl, anchors));
    }

    private List<WebElementAnchorInput> twoAnchors() {
        return List.of(
                new WebElementAnchorInput("testid", "login-btn", 0.9, "强锚"),
                new WebElementAnchorInput("css", "#login > button", 0.6, "兜底"));
    }

    @Test
    void createNormalizesPageKeyAndStartsAsCandidate() {
        long projectId = createProject();
        WebElementView created = createElement(projectId, "登录按钮",
                "https://Shop.Example.COM/pets/123?tab=1", twoAnchors());
        assertNotNull(created.id());
        assertEquals(STATE_CANDIDATE, created.state(), "new elements enter as candidate");
        assertEquals(1, created.revision());
        assertEquals("https://shop.example.com/pets/{var}", created.pageKey(),
                "origin lowercased, variable segment folded through the shared rule (W4)");
        assertEquals(2, created.anchors().size());
        assertEquals("testid", created.anchors().get(0).type(), "anchor order preserved");
    }

    @Test
    void concreteAndTemplateUrlsLandOnTheSamePageKey() {
        long projectId = createProject();
        WebElementView first = createElement(projectId, "按钮甲",
                "https://shop.example.com/orders/20261011001", twoAnchors());
        WebElementView second = createElement(projectId, "按钮乙",
                "https://shop.example.com/orders/{id}", twoAnchors());
        assertEquals(first.pageKey(), second.pageKey(),
                "asset rows must not fragment across concrete vs template paths");
    }

    @Test
    void duplicateNameOnSamePageRefusedWithHumanReadableMessage() {
        long projectId = createProject();
        createElement(projectId, "登录按钮", "https://shop.example.com/login", twoAnchors());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> createElement(projectId, "登录按钮", "https://shop.example.com/login", twoAnchors()));
        assertEquals(40904, ex.getCode(), "unique clash maps to the shared resource-conflict code");
        assertTrue(ex.getMessage().contains("登录按钮"),
                "the refusal names the element: " + ex.getMessage());
    }

    @Test
    void anchorTypeAndStrengthValueDomainEnforced() {
        long projectId = createProject();
        ServiceException badType = assertThrows(ServiceException.class,
                () -> createElement(projectId, "坏锚元素", "https://shop.example.com/x",
                        List.of(new WebElementAnchorInput("id", "#login", 0.5, null))));
        assertEquals(40001, badType.getCode());
        assertTrue(badType.getMessage().contains("id"), "the refusal names the offending type");

        ServiceException badStrength = assertThrows(ServiceException.class,
                () -> createElement(projectId, "坏强度元素", "https://shop.example.com/x",
                        List.of(new WebElementAnchorInput("css", "#login", 1.5, null))));
        assertEquals(40001, badStrength.getCode());

        assertThrows(ServiceException.class,
                () -> createElement(projectId, "空锚元素", "https://shop.example.com/x", List.of()));
    }

    @Test
    void legalTransitionsWalkTheTableAndIllegalOnesAreRefused() {
        long projectId = createProject();
        WebElementView created = createElement(projectId, "迁移元素", "https://shop.example.com/mv", twoAnchors());

        WebElementView confirmed = webElementService.changeState(
                created.id(), STATE_CONFIRMED, created.revision());
        assertEquals(STATE_CONFIRMED, confirmed.state());
        assertEquals(2, confirmed.revision(), "each transition consumes the optimistic lock");

        WebElementView stale = webElementService.changeState(
                confirmed.id(), STATE_STALE, confirmed.revision());
        assertEquals(STATE_STALE, stale.state());

        WebElementView reconfirmed = webElementService.changeState(
                stale.id(), STATE_CONFIRMED, stale.revision());
        assertEquals(STATE_CONFIRMED, reconfirmed.state());

        ServiceException backToCandidate = assertThrows(ServiceException.class,
                () -> webElementService.changeState(reconfirmed.id(), STATE_CANDIDATE, reconfirmed.revision()));
        assertEquals(40902, backToCandidate.getCode(), "confirmed -> candidate is off the table");

        WebElementView retired = webElementService.changeState(
                reconfirmed.id(), STATE_RETIRED, reconfirmed.revision());
        ServiceException revived = assertThrows(ServiceException.class,
                () -> webElementService.changeState(retired.id(), STATE_CONFIRMED, retired.revision()));
        assertEquals(40902, revived.getCode(), "retired is terminal");
    }

    @Test
    void staleWritesAndStaleCasTokensRefusedWith40901() {
        long projectId = createProject();
        WebElementView created = createElement(projectId, "并发元素", "https://shop.example.com/ca", twoAnchors());

        ServiceException badToken = assertThrows(ServiceException.class,
                () -> webElementService.changeState(created.id(), STATE_CONFIRMED, created.revision() + 5));
        assertEquals(40901, badToken.getCode(), "state door shares the 40901 family");

        WebElementView updated = webElementService.updateAnchors(created.id(),
                List.of(new WebElementAnchorInput("xpath", "//button[@id='login']", 0.8, "改版后新锚")),
                created.revision());
        assertEquals(2, updated.revision());
        assertEquals(1, updated.anchors().size());

        ServiceException staleAnchors = assertThrows(ServiceException.class,
                () -> webElementService.updateAnchors(created.id(), twoAnchors(), created.revision()));
        assertEquals(40901, staleAnchors.getCode());
        assertTrue(staleAnchors.getMessage().contains("2"), "the refusal names the current revision");
    }

    @Test
    void everyTransitionAppendsOneLedgerEventRow() {
        long projectId = createProject();
        WebElementView created = createElement(projectId, "留痕元素", "https://shop.example.com/log", twoAnchors());
        webElementService.changeState(created.id(), STATE_CONFIRMED, created.revision());
        webElementService.changeState(created.id() + 0, STATE_STALE, created.revision() + 1);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_task_event WHERE job_kind = 'element-ledger' AND job_ref = ?",
                Integer.class, String.valueOf(created.id()));
        assertEquals(2, rows, "one event row per transition, replayable in the tail drawer");
    }

    @Test
    void pageGroupingSummarizesLifecycleCounts() {
        long projectId = createProject();
        WebElementView one = createElement(projectId, "甲按钮", "https://shop.example.com/cart/7", twoAnchors());
        createElement(projectId, "乙按钮", "https://shop.example.com/cart/9", twoAnchors());
        webElementService.changeState(one.id(), STATE_CONFIRMED, one.revision());

        List<WebElementPageView> pages = webElementService.pagesOfProject(projectId);
        assertEquals(1, pages.size(), "both elements fold onto one page");
        WebElementPageView page = pages.get(0);
        assertEquals("https://shop.example.com/cart/{var}", page.pageKey());
        assertEquals(2, page.total());
        assertEquals(1, page.confirmedCount());
        assertEquals(1, page.candidateCount());
        assertEquals(0, page.staleCount());
    }

    @Test
    void findByIdReturnsTheStoredView() {
        long projectId = createProject();
        WebElementView created = createElement(projectId, "回读元素", "https://shop.example.com/r", twoAnchors());
        WebElementView read = webElementService.findById(created.id());
        assertEquals(created.name(), read.name());
        assertEquals(created.pageKey(), read.pageKey());
        assertNotNull(read.createdAt());
    }

    // ---- rename door ----

    @Test
    void renameUpdatesNameBumpsRevisionAndAudits() {
        long projectId = createProject();
        WebElementView created = createElement(projectId, "错名字按钮", "https://shop.example.com/rn", twoAnchors());
        WebElementView renamed = webElementService.rename(created.id(), "登录按钮", created.revision());
        assertEquals("登录按钮", renamed.name());
        assertEquals(created.revision() + 1, renamed.revision(), "rename consumes the CAS token like every write door");
        Integer auditRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_task_event WHERE job_kind = 'element-ledger' AND job_ref = ?"
                        + " AND summary LIKE '%重命名%'",
                Integer.class, String.valueOf(created.id()));
        assertEquals(1, auditRows, "one audit row naming both the old and the new name");
    }

    @Test
    void renameToSameNameIsANoOpWithoutRevisionChurn() {
        long projectId = createProject();
        WebElementView created = createElement(projectId, "同名元素", "https://shop.example.com/same", twoAnchors());
        WebElementView same = webElementService.rename(created.id(), "同名元素", created.revision());
        assertEquals(created.revision(), same.revision(), "replaying the current name must not bump revision");
    }

    @Test
    void renameRefusesDuplicateOnTheSamePageButAllowsItOnAnotherPage() {
        long projectId = createProject();
        WebElementView first = createElement(projectId, "提交按钮", "https://shop.example.com/orders/1", twoAnchors());
        createElement(projectId, "暂存按钮", "https://shop.example.com/orders/9", twoAnchors());
        WebElementView second = createElement(projectId, "草稿按钮", "https://shop.example.com/cart/2", twoAnchors());

        // The unique gate is (project, folded page_key, name): distinct pages
        // may share names (orders/{var} and cart/{var} fold apart).
        WebElementView moved = webElementService.rename(second.id(), "提交按钮", second.revision());
        assertEquals("提交按钮", moved.name());

        ServiceException clash = assertThrows(ServiceException.class,
                () -> webElementService.rename(first.id(), "暂存按钮", first.revision()));
        assertEquals(40904, clash.getCode(), "same-page duplicate rides the existing uq gate posture");
        assertTrue(clash.getMessage().contains("暂存按钮"), "the refusal names the conflicting value");
    }

    @Test
    void renameWithStaleTokenRefusedWith40901() {
        long projectId = createProject();
        WebElementView created = createElement(projectId, "旧令牌元素", "https://shop.example.com/stale", twoAnchors());
        webElementService.updateAnchors(created.id(), twoAnchors(), created.revision());
        ServiceException refused = assertThrows(ServiceException.class,
                () -> webElementService.rename(created.id(), "新名", created.revision()));
        assertEquals(40901, refused.getCode(), "rename shares the 40901 family");
    }

    @Test
    void renameRejectsBlankNameWithActionableWording() {
        long projectId = createProject();
        WebElementView created = createElement(projectId, "空名元素", "https://shop.example.com/blank", twoAnchors());
        ServiceException refused = assertThrows(ServiceException.class,
                () -> webElementService.rename(created.id(), "   ", created.revision()));
        assertEquals(40001, refused.getCode(), "blank names refuse with the shared validation posture");
        assertTrue(refused.getMessage().contains("不能为空"), "the refusal names the offending field");
    }

    @Test
    void dispatchByReferenceFollowsTheRenamedName() {
        long projectId = createProject();
        WebElementView created = createElement(projectId, "旧引用名", "https://shop.example.com/ref", twoAnchors());
        assertEquals(1, webElementService.findForDispatch(projectId, List.of("旧引用名"), true).size());
        webElementService.rename(created.id(), "新引用名", created.revision());
        assertEquals(1, webElementService.findForDispatch(projectId, List.of("新引用名"), true).size(),
                "{{ELEM:名称}} references resolve on the new name right after the rename");
        assertEquals(0, webElementService.findForDispatch(projectId, List.of("旧引用名"), true).size(),
                "the stale name never resolves again — no half-renamed window");
    }
}
