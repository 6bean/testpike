package com.sixbean.testpike.core.webelement.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.WebElementLedgerFixtures;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt-summary read door (WE batch S5, piece A): the generation
 * prompts ask core for the CONFIRMED elements of one project, best anchors
 * first, capped rows — never the whole table. Ordering is confidence desc
 * with an id tie-break so repeated prompts stay byte-stable for the provider
 * cache; the limit clamps into 1..500 server-side regardless of caller junk.
 * <p>Real dev database, no mocks. Cleanup deletes only rows created here.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class WebElementPromptSummaryTest {

    @Autowired
    private WebElementService webElementService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> fixtureProjectIds = new ArrayList<>();

    private long createProject() {
        return WebElementLedgerFixtures.createProject(jdbcTemplate, projectService,
                fixtureProjectIds, "摘要门项目");
    }

    @AfterEach
    void cleanUp() {
        for (Long projectId : fixtureProjectIds) {
            WebElementLedgerFixtures.purge(jdbcTemplate, projectId);
        }
        fixtureProjectIds.clear();
    }

    private List<WebElementAnchorInput> oneAnchor(String css) {
        return List.of(new WebElementAnchorInput("css", css, 0.8, "摘要门锚"));
    }

    private void bumpConfidence(long elementId, double value) {
        jdbcTemplate.update("UPDATE tp_web_element SET confidence = ? WHERE id = ?",
                value, elementId);
    }

    @Test
    void onlyConfirmedRowsAreSummarized() {
        long projectId = createProject();
        WebElementLedgerFixtures.confirmed(webElementService, projectId, "确定按钮",
                "https://shop.example.com/ok", oneAnchor("#ok-btn"));
        webElementService.create(new com.sixbean.testpike.core.webelement.api.dto.WebElementInput(
                projectId, "候选按钮", "https://shop.example.com/ok", oneAnchor("#cand")));
        WebElementView stale = WebElementLedgerFixtures.confirmed(webElementService, projectId,
                "陈旧按钮", "https://shop.example.com/ok", oneAnchor("#stale"));
        webElementService.changeState(stale.id(), 2, stale.revision());

        List<WebElementView> rows = webElementService.promptSummary(projectId, null, 50);
        assertEquals(1, rows.size(), "candidate and stale rows never travel into prompts");
        assertEquals("确定按钮", rows.get(0).name());
    }

    @Test
    void rowsOrderByConfidenceDescWithIdTieBreak() {
        long projectId = createProject();
        WebElementView low = WebElementLedgerFixtures.confirmed(webElementService, projectId,
                "低置信", "https://shop.example.com/ord", oneAnchor("#low"));
        WebElementView mid = WebElementLedgerFixtures.confirmed(webElementService, projectId,
                "中置信", "https://shop.example.com/ord", oneAnchor("#mid"));
        WebElementView high = WebElementLedgerFixtures.confirmed(webElementService, projectId,
                "高置信", "https://shop.example.com/ord", oneAnchor("#high"));
        bumpConfidence(low.id(), 0.2);
        bumpConfidence(mid.id(), 0.9);
        bumpConfidence(high.id(), 0.9);

        List<WebElementView> rows = webElementService.promptSummary(projectId, null, 50);
        assertEquals(List.of("中置信", "高置信", "低置信"), rows.stream()
                .map(WebElementView::name).toList(),
                "confidence desc first, insertion id keeps equal rows byte-stable");
    }

    @Test
    void limitClampsIntoOneToFiveHundred() {
        long projectId = createProject();
        for (int i = 0; i < 3; i++) {
            WebElementLedgerFixtures.confirmed(webElementService, projectId,
                    "元素" + i, "https://shop.example.com/lim", oneAnchor("#b" + i));
        }
        assertEquals(1, webElementService.promptSummary(projectId, null, 0).size(),
                "limit 0 clamps up to 1 (never an empty-cap loophole, never unbounded)");
        assertEquals(2, webElementService.promptSummary(projectId, null, 2).size());
        assertEquals(3, webElementService.promptSummary(projectId, null, 9999).size(),
                "absurd limits clamp to the 500 ceiling; small projects return everything");
    }

    @Test
    void pageKeyFilterUsesTheFoldedLiteralKey() {
        long projectId = createProject();
        WebElementLedgerFixtures.confirmed(webElementService, projectId, "购物车按钮",
                "https://shop.example.com/cart/7", oneAnchor("#cart"));
        WebElementLedgerFixtures.confirmed(webElementService, projectId, "首页按钮",
                "https://shop.example.com/home", oneAnchor("#home"));

        List<WebElementView> rows = webElementService.promptSummary(projectId,
                "https://shop.example.com/cart/{var}", 50);
        assertEquals(1, rows.size(), "the page slice speaks the shared folded key (W4)");
        assertEquals("购物车按钮", rows.get(0).name());
        assertTrue(webElementService.promptSummary(projectId, "  ", 50).size() == 2,
                "blank pageKey means the whole project slice");
    }
}
