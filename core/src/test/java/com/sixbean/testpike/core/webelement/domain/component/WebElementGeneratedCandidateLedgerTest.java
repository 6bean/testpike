package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.core.support.CaseFixtureRig;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save-door candidate ingestion for invented locators (WE batch S5,
 * piece D): when an AI-generated script saves a step whose target is an
 * INLINE locator (the model invented an anchor instead of referencing the
 * ledger with {{ELEM:name}}), the anchor must enter the page element library
 * as a CANDIDATE row through the survey collector's idempotent upsert — the
 * approval panel is the only door into confirmed. Ledger references never
 * register, steps behind an unattributable (template) goto url are skipped
 * honestly, replays write nothing new, and the non-AI save doors (manual
 * editing) never touch the ledger. Registration is fail-soft: it never
 * blocks the save. Real dev database, no mocks; cleanup deletes only rows
 * created here.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class WebElementGeneratedCandidateLedgerTest {

    private static final String PAGE_A = "https://shop-ledger.test/login";
    private static final String PAGE_B = "https://shop-ledger.test/cart";

    @Autowired
    private WebElementGeneratedCandidateLedger ledger;
    @Autowired
    private WebElementService webElementService;
    @Autowired
    private ScriptService scriptService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> fixtureProjectIds = new ArrayList<>();

    private long createProject() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        return ProjectFixtureRig.create(jdbcTemplate, projectService, 1L,
                fixtureProjectIds, "生成链候选入册项目" + code, code);
    }

    @AfterEach
    void cleanUp() {
        for (Long projectId : fixtureProjectIds) {
            jdbcTemplate.update(
                    "DELETE FROM tp_script WHERE case_id IN"
                            + " (SELECT id FROM tp_test_case WHERE project_id = ?)", projectId);
            jdbcTemplate.update(
                    "DELETE FROM tp_test_case WHERE project_id = ?", projectId);
            jdbcTemplate.update(
                    "DELETE FROM tp_web_element WHERE project_id = ?", projectId);
            jdbcTemplate.update(
                    "DELETE FROM tp_user_project WHERE project_id = ?", projectId);
            jdbcTemplate.update(
                    "DELETE FROM tp_project WHERE id = ?", projectId);
        }
        fixtureProjectIds.clear();
    }

    /** Invented inline anchors behind resolvable gotos land as candidates. */
    @Test
    void inventedInlineLocatorsRegisterAsCandidates() {
        long projectId = createProject();
        ledger.recordGenerated(projectId, actionJson("""
                {"action":"goto","target":{"url":"%s"}},
                {"action":"click","target":{"css":"#login-btn","text":"登录"}},
                {"action":"input","target":{"placeholder":"手机号"},"value":"1"},
                {"action":"goto","target":{"url":"%s"}},
                {"action":"expect","target":{"text":"小计"}}
                """.formatted(PAGE_A, PAGE_B)));

        List<WebElementView> pageA = webElementService.listByProject(projectId, pageKey(PAGE_A));
        List<WebElementView> pageB = webElementService.listByProject(projectId, pageKey(PAGE_B));
        assertEquals(2, pageA.size(), "each distinct invented anchor set opens its own row");
        assertEquals(1, pageB.size());
        assertTrue(pageA.stream().allMatch(row -> row.state() == 0),
                "save-door registrations enter as CANDIDATE");
        WebElementView loginRow = pageA.stream()
                .filter(row -> row.anchors().stream().anyMatch(
                        a -> "css".equals(a.type()) && "#login-btn".equals(a.value())))
                .findFirst().orElseThrow();
        // one step's own anchors merge into its row (css + text of the click)
        assertTrue(loginRow.anchors().stream().anyMatch(a -> "text".equals(a.type())),
                "the step's text anchor merged onto the same row");
        assertTrue(pageA.stream().flatMap(row -> row.anchors().stream())
                        .allMatch(a -> a.note() != null && a.note().contains(
                                WebElementGeneratedCandidateLedger.ORIGIN_NOTE)),
                "invented anchors carry the generation origin note");
        assertTrue(pageB.get(0).anchors().stream()
                        .anyMatch(a -> "text".equals(a.type()) && "小计".equals(a.value())),
                "expect-side invented text anchor registers on its own page");
    }

    /** {{ELEM:name}} ledger references never re-register; replays write nothing. */
    @Test
    void ledgerReferencesAndReplaysRegisterNothing() {
        long projectId = createProject();
        String steps = """
                {"action":"goto","target":{"url":"%s"}},
                {"action":"click","target":"{{ELEM:登录按钮}}"},
                {"action":"click","target":{"css":"#checkout"}}
                """.formatted(PAGE_A);
        ledger.recordGenerated(projectId, actionJson(steps));
        int rows = webElementService.listByProject(projectId, pageKey(PAGE_A)).size();
        assertEquals(1, rows, "the {{ELEM:}} string target registers nothing");

        ledger.recordGenerated(projectId, actionJson(steps));
        assertEquals(rows, webElementService.listByProject(projectId, pageKey(PAGE_A)).size(),
                "replay of the same document writes nothing new");
    }

    /** Steps behind an unattributable template goto are skipped, never guessed. */
    @Test
    void templatedGotoUrlSkipsAttribution() {
        long projectId = createProject();
        ledger.recordGenerated(projectId, actionJson("""
                {"action":"goto","target":{"url":"{{ENV:BASE_URL}}/login"}},
                {"action":"click","target":{"css":"#tpl-skip"}}
                """));
        assertTrue(webElementService.listByProject(projectId, null).isEmpty(),
                "no honest page = no registration");
    }

    /** The save door wires it in: AI saves register, manual saves never do. */
    @Test
    void saveDoorRegistersForAiGeneratedScriptsOnly() {
        long projectId = createProject();
        long caseId = CaseFixtureRig.create(caseService, 1L, projectId, "生成链候选入册用例");
        scriptService.save(new ScriptSaveInput(caseId, "v2",
                actionJson("""
                        {"action":"goto","target":{"url":"%s"}},
                        {"action":"click","target":{"css":"#ai-door"}}
                        """.formatted(PAGE_A)), 1));
        assertEquals(1, webElementService.listByProject(projectId, pageKey(PAGE_A)).size(),
                "AI-generated save lands the invented anchor");

        // the prompt declaration covers the AI stage-2 door only: an undeclared
        // codegen save of the same shape must leave the ledger untouched
        scriptService.save(new ScriptSaveInput(caseId, "v2",
                actionJson("""
                        {"action":"goto","target":{"url":"%s"}},
                        {"action":"click","target":{"css":"#codegen-door"}}
                        """.formatted(PAGE_A)), 2));
        List<WebElementView> rows = webElementService.listByProject(projectId, pageKey(PAGE_A));
        assertEquals(1, rows.size(), "non-AI doors never touch the ledger");
        assertTrue(rows.get(0).anchors().stream()
                        .noneMatch(a -> "#codegen-door".equals(a.value())),
                "codegen anchor stayed out of the ledger");
    }

    /** Fold the page key through the same single source the ledger reads. */
    private String pageKey(String url) {
        String authority = url.substring("https://".length());
        int slash = authority.indexOf('/');
        return "https://" + authority.substring(0, slash)
                + (slash < 0 ? "/" : authority.substring(slash));
    }

    private static String actionJson(String steps) {
        return "{\"version\":\"v2\",\"steps\":[" + steps + "]}";
    }
}
