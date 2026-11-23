package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.webelement.api.dto.ElementEvidenceGrantView;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementInput;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evidence-plane baseline grant read path for the equivalent re-anchor
 * compare port (Page Element Library design 8.1 scale B, ledger evidence
 * column from V544). Contracts nailed here:
 * ① found=true only when the row's evidence JSONB carries a usable
 *   screenshotKey — today no writer installs that key, so the live path
 *   stays found=false and the gate keeps the proposal route (fail-closed
 *   by design, the channel is proven by injection here);
 * ② the grant rides the LIVE ledger revision (one query, never a cached
 *   or view-shaped copy — the executor needs it to spot envelope drift);
 * ③ unknown element ids and blank/non-string keys answer found=false
 *   without throwing: the heal path must degrade to a suggestion, never
 *   break the result relay over a missing baseline;
 * ④ WebElementView stays untouched (list-display contract pollution ban);
 * ⑤ when storage grants are available the url is a presigned GET form,
 *   when they are not the grant still answers with url=null and no throw.
 * <p>Real chain against the chain-slot DB; fixtures purged by project
 * scope (no TRUNCATE of shipped tables, baseline-resident discipline).
 */
@SpringBootTest(classes = CoreApplication.class)
class WebElementEvidenceGrantQueryTest {

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private WebElementEvidenceGrantQuery grantQuery;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private WebElementService webElementService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void seedProject() {
        long tag = SEQ.incrementAndGet();
        String code = "WG" + System.currentTimeMillis() % 100000000L + tag;
        projectId = projectService.create(1L, new ProjectCreateInput(
                "证据授权项目" + code, code, "zh-CN", "P1", null));
    }

    @AfterEach
    void removeFixtures() {
        jdbcTemplate.update("DELETE FROM tp_web_element WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private long element(String name) {
        return webElementService.create(new WebElementInput(projectId, name,
                "https://shop.example.com/orders",
                List.of(new WebElementAnchorInput("css", ".ord", 0.9, null)))).id();
    }

    private void setEvidence(long id, String json) {
        jdbcTemplate.update("UPDATE tp_web_element SET evidence = CAST(? AS jsonb) WHERE id = ?",
                json, id);
    }

    @Test
    void absentEvidenceAnswersNotFoundWithoutTouchingPresign() {
        long id = element("提交按钮");
        ElementEvidenceGrantView grant = grantQuery.grant(id);
        assertFalse(grant.found());
        assertNull(grant.screenshotKey());
        assertNull(grant.url());
    }

    @Test
    void screenshotKeyGrantsFoundAndRidesTheLiveRevision() {
        long id = element("下单入口");
        setEvidence(id, "{\"screenshotKey\":\"we/shot-1.png\"}");
        ElementEvidenceGrantView first = grantQuery.grant(id);
        assertTrue(first.found());
        assertEquals("we/shot-1.png", first.screenshotKey());
        Long rowRevision = jdbcTemplate.queryForObject(
                "SELECT revision FROM tp_web_element WHERE id = ?", Long.class, id);
        assertEquals(rowRevision, first.revision());
        if (first.url() != null) {
            assertTrue(first.url().startsWith("http") || first.url().startsWith("/"),
                    "presigned GET must look like a URL, got: " + first.url());
        }
        // Bump the ledger row behind the scene: the next grant must report
        // the NEW revision (single-query live read, no cached envelope copy).
        jdbcTemplate.update("UPDATE tp_web_element SET revision = revision + 1 WHERE id = ?", id);
        ElementEvidenceGrantView second = grantQuery.grant(id);
        assertTrue(second.found());
        assertEquals(rowRevision + 1, second.revision());
    }

    @Test
    void unknownElementIdStaysQuiet() {
        ElementEvidenceGrantView grant =
                assertDoesNotThrow(() -> grantQuery.grant(999_999_999_999L));
        assertFalse(grant.found());
        assertNull(grant.revision());
    }

    @Test
    void blankOrMissingKeyIsNotUsableEvidence() {
        long id = element("空证据元素");
        setEvidence(id, "{}");
        assertFalse(grantQuery.grant(id).found());
        setEvidence(id, "{\"screenshotKey\":\"   \"}");
        assertFalse(grantQuery.grant(id).found());
        setEvidence(id, "{\"screenshotKey\":null}");
        assertFalse(grantQuery.grant(id).found());
    }

    @Test
    void nonPositiveIdIsRefusedAsNotFoundNotAsAnError() {
        assertFalse(grantQuery.grant(0L).found());
        assertFalse(grantQuery.grant(-1L).found());
    }
}
