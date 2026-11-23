package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.webelement.api.dto.ElementAnchorsClaimBinding;
import com.sixbean.testpike.core.webelement.api.dto.ElementSnapshot;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dispatch-side element reference assembly (WE batch S1, piece 1). Covers
 * the three server-side promises of the reference protocol:
 * <ol>
 * <li>{@code {{ELEM:name}}} placeholder targets are normalised to the object
 * form {@code {"by":"element","name":name}} at assembly, inline targets
 * are byte-identical pass-through (existing behaviour zero drift);</li>
 * <li>the claim envelope carries the referenced anchor-set snapshot (the
 * executor must never call back into core for ledger rows), confirmed
 * rows only unless the action JSON explicitly declares allowCandidate;</li>
 * <li>anchor ordering is computed here once (strength x feedback confidence)
 * so the executor only tries anchors in the given order.</li>
 * </ol>
 * <p>Real dev database, no mocks. Cleanup deletes only rows created here.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class WebElementDispatchSnapshotTest {

    private static final int STATE_CANDIDATE = 0;
    private static final int STATE_CONFIRMED = 1;
    private static final int STATE_STALE = 2;

    @Autowired
    private WebElementDispatchSnapshot dispatchSnapshot;
    @Autowired
    private WebElementService webElementService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper mapper;

    private final List<Long> fixtureProjectIds = new ArrayList<>();

    private long createProject() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        return ProjectFixtureRig.create(jdbcTemplate, projectService, 1L,
                fixtureProjectIds, "派发快照项目" + code, code);
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

    private WebElementView confirmedElement(long projectId, String name, String pageUrl,
                                            List<WebElementAnchorInput> anchors) {
        WebElementView created = createElement(projectId, name, pageUrl, anchors);
        return webElementService.changeState(created.id(), STATE_CONFIRMED, created.revision());
    }

    private String clickAction(String... targets) {
        StringBuilder steps = new StringBuilder();
        for (int i = 0; i < targets.length; i++) {
            if (i > 0) {
                steps.append(',');
            }
            steps.append("{\"action\":\"click\",\"target\":").append(targets[i]).append('}');
        }
        return "{\"version\":\"v2\",\"setup\":[],\"steps\":[" + steps + "]}";
    }

    @Test
    void placeholderTargetNormalisesToObjectForm() throws Exception {
        long projectId = createProject();
        String action = "{\"version\":\"v2\",\"setup\":[{\"action\":\"goto\","
                + "\"target\":{\"url\":\"https://shop.example.com/login\"}}],"
                + "\"steps\":[{\"action\":\"click\",\"target\":\"{{ELEM:登录按钮}}\"},"
                + "{\"action\":\"input\",\"target\":{\"css\":\"#pwd\"},\"value\":\"x\"}]}";

        WebElementDispatchSnapshot.Envelope envelope =
                dispatchSnapshot.assemble(projectId, 1, action);

        JsonNode root = mapper.readTree(envelope.actionJson());
        JsonNode target = root.path("steps").path(0).path("target");
        assertTrue(target.isObject(), "placeholder target must normalise to an object");
        assertEquals("element", target.path("by").asText());
        assertEquals("登录按钮", target.path("name").asText());
        assertEquals("https://shop.example.com/login",
                root.path("setup").path(0).path("target").path("url").asText(),
                "inline targets travel untouched (zero-drift rule)");
        assertEquals("#pwd", root.path("steps").path(1).path("target").path("css").asText());
        assertNotNull(envelope.anchors(), "a referenced name always emits a snapshot entry");
        assertTrue(envelope.anchors().elements().containsKey("登录按钮"));
        assertTrue(envelope.anchors().elements().get("登录按钮").isEmpty(),
                "no ledger row yet: empty snapshot, honest executor-side failure later");
    }

    @Test
    void documentWithoutReferencesPassesThroughByteIdentical() {
        long projectId = createProject();
        String action = clickAction("{\"css\":\"#btn\"}", "{\"text\":\"下单\"}");

        WebElementDispatchSnapshot.Envelope envelope =
                dispatchSnapshot.assemble(projectId, 1, action);

        assertSame(action, envelope.actionJson(),
                "no reference = the very same string, no re-serialisation drift");
        assertNull(envelope.anchors(), "no reference = trailing key not emitted");
    }

    @Test
    void alreadyNormalisedObjectReferenceStillLoadsSnapshot() {
        long projectId = createProject();
        confirmedElement(projectId, "确定按钮", "https://shop.example.com/orders",
                List.of(new WebElementAnchorInput("testid", "ok-btn", 0.9, "强锚")));

        WebElementDispatchSnapshot.Envelope envelope = dispatchSnapshot.assemble(
                projectId, 1, clickAction("{\"by\":\"element\",\"name\":\"确定按钮\"}"));

        assertNotNull(envelope.anchors(), "the object form must also collect names");
        assertEquals(1, envelope.anchors().elements().get("确定按钮").size());
        assertEquals(STATE_CONFIRMED, envelope.anchors().elements().get("确定按钮").get(0).state());
    }

    @Test
    void envelopeCarriesReAnchorFloorAndRowRevision() {
        long projectId = createProject();
        WebElementView created = confirmedElement(projectId, "重锚按钮",
                "https://shop.example.com/cart",
                List.of(new WebElementAnchorInput("testid", "cart-btn", 0.9, "强锚")));

        ElementAnchorsClaimBinding binding = dispatchSnapshot.assemble(
                projectId, 1, clickAction("\"{{ELEM:重锚按钮}}\"")).anchors();

        assertNotNull(binding, "a referenced name emits the trailing binding");
        assertEquals(0.85, binding.reAnchorSimFloor(), 1e-9,
                "the envelope carries the hot config floor so scale B is"
                        + " gateable without an executor-side config read");
        assertEquals(created.revision(),
                binding.elements().get("重锚按钮").get(0).revision(),
                "dispatch-time revision rides each snapshot row (stale-write refusal)");
    }

    @Test
    void confirmedRowsOnlyUntilAllowCandidateDeclared() {
        long projectId = createProject();
        WebElementView candidate = createElement(projectId, "登录按钮",
                "https://shop.example.com/login",
                List.of(new WebElementAnchorInput("css", "#login", 0.6, "候选锚")));
        String action = clickAction("\"{{ELEM:登录按钮}}\"");

        ElementAnchorsClaimBinding without =
                dispatchSnapshot.assemble(projectId, 1, action).anchors();
        assertNotNull(without);
        assertTrue(without.elements().get("登录按钮").isEmpty(),
                "candidate rows stay out of the snapshot by default");
        assertFalse(without.allowCandidate());

        String trialAction = "{\"elementAnchors\":{\"allowCandidate\":true},"
                + "\"setup\":[],\"steps\":[{\"action\":\"click\",\"target\":\"{{ELEM:登录按钮}}\"}]}";
        ElementAnchorsClaimBinding withCandidate =
                dispatchSnapshot.assemble(projectId, 1, trialAction).anchors();
        assertEquals(1, withCandidate.elements().get("登录按钮").size());
        assertEquals(STATE_CANDIDATE, withCandidate.elements().get("登录按钮").get(0).state());
        assertTrue(withCandidate.allowCandidate());

        webElementService.changeState(candidate.id(), STATE_CONFIRMED, candidate.revision());
        ElementAnchorsClaimBinding confirmedNow =
                dispatchSnapshot.assemble(projectId, 1, action).anchors();
        assertEquals(1, confirmedNow.elements().get("登录按钮").size(),
                "confirmed rows travel in the snapshot without any opt-in");
    }

    @Test
    void staleRowsNeverEnterTheSnapshot() {
        long projectId = createProject();
        WebElementView confirmed = confirmedElement(projectId, "查询按钮",
                "https://shop.example.com/search",
                List.of(new WebElementAnchorInput("testid", "q", 0.9, "强锚")));
        webElementService.changeState(confirmed.id(), STATE_STALE, confirmed.revision());

        ElementAnchorsClaimBinding binding = dispatchSnapshot.assemble(
                projectId, 1, clickAction("\"{{ELEM:查询按钮}}\"")).anchors();
        assertTrue(binding.elements().get("查询按钮").isEmpty(),
                "stale rows must not be dispatched (repair goes through the human gate)");
    }

    @Test
    void anchorsRankedByStrengthWithPriorFallback() {
        long projectId = createProject();
        WebElementView weakFirst = createElement(projectId, "保存按钮",
                "https://shop.example.com/form",
                List.of(new WebElementAnchorInput("xpath", "//button[7]", 0.4, "长链"),
                        new WebElementAnchorInput("testid", "save", 0.9, "强锚"),
                        new WebElementAnchorInput("css", "form > div > div > button", 0.5, "无评分")));
        webElementService.changeState(weakFirst.id(), STATE_CONFIRMED, weakFirst.revision());
        // The create door refuses null strengths, so the prior-fallback branch is
        // only reachable through a legacy/dirty stored anchor: drop the key in
        // storage to reproduce that row shape honestly.
        jdbcTemplate.update("UPDATE tp_web_element SET anchors ="
                + " jsonb_set(anchors, '{2}', ((anchors->2) - 'strength')) WHERE id = ?",
                weakFirst.id());

        ElementAnchorsClaimBinding binding = dispatchSnapshot.assemble(
                projectId, 1, clickAction("\"{{ELEM:保存按钮}}\"")).anchors();
        List<ElementSnapshot> snapshots = binding.elements().get("保存按钮");
        assertEquals(1, snapshots.size());
        List<WebElementAnchorInput> ordered = snapshots.get(0).anchors();
        assertEquals(3, ordered.size());
        assertEquals("testid", ordered.get(0).type(),
                "highest strength x confidence first (formula single source, server side)");
        assertEquals("css", ordered.get(1).type(),
                "missing strength falls back to the type prior (css short chain above long xpath)");
        assertEquals("xpath", ordered.get(2).type());
    }

    @Test
    void multiNameDispatchReadsTheLedgerOnce() {
        long projectId = createProject();
        confirmedElement(projectId, "登录按钮", "https://shop.example.com/login",
                List.of(new WebElementAnchorInput("testid", "login", 0.9, "强锚")));
        confirmedElement(projectId, "密码框", "https://shop.example.com/login",
                List.of(new WebElementAnchorInput("placeholder", "密码", 0.8, "占位锚")));

        ElementAnchorsClaimBinding binding = dispatchSnapshot.assemble(projectId, 1,
                clickAction("\"{{ELEM:登录按钮}}\"", "\"{{ELEM:密码框}}\"",
                        "{\"by\":\"element\",\"name\":\"未建档\"}")).anchors();
        assertEquals(2, binding.elements().get("登录按钮").size()
                + binding.elements().get("密码框").size());
        assertTrue(binding.elements().get("未建档").isEmpty(),
                "referenced but unregistered names still appear with an honest empty set");
        assertEquals(3, binding.elements().size(), "one entry per referenced name");
    }

    @Test
    void nonBrowserTaskAndDirtyJsonFailSafePassThrough() {
        long projectId = createProject();
        String action = clickAction("\"{{ELEM:任意}}\"");
        WebElementDispatchSnapshot.Envelope other = dispatchSnapshot.assemble(projectId, 2, action);
        assertSame(action, other.actionJson(), "non-browser task keeps the legacy shape");
        assertNull(other.anchors());

        String dirty = "{\"version\\\": broken";
        WebElementDispatchSnapshot.Envelope envelope = dispatchSnapshot.assemble(projectId, 1, dirty);
        assertSame(dirty, envelope.actionJson(), "dirty JSON must never break the claim round");
        assertNull(envelope.anchors());
    }

    @Test
    void declaredViewportClassResolvesVariantAnchorsWithTopLevelFallback() {
        long projectId = createProject();
        WebElementView row = confirmedElement(projectId, "移动登录按钮",
                "https://shop.example.com/login",
                List.of(new WebElementAnchorInput("css", "#login", 0.6, "默认档锚")));
        jdbcTemplate.update("UPDATE tp_web_element SET variants = ?::jsonb WHERE id = ?",
                "[{\"viewport\":\"mobile\",\"anchors\":[{\"type\":\"testid\",\"value\":\"m-login\","
                        + "\"strength\":0.9,\"note\":\"移动档\"}]},"
                        + "{\"viewport\":\"tablet\",\"anchors\":[{\"type\":\"css\",\"value\":\"#t-login\","
                        + "\"strength\":0.85,\"note\":\"平板档\"}]}]",
                row.id());

        ElementSnapshot mobile = dispatchSnapshot.assemble(projectId, 1,
                classAction("mobile")).anchors().elements().get("移动登录按钮").get(0);
        assertEquals("m-login", mobile.anchors().get(0).value(),
                "a declared viewport class resolves to that variant's anchor set (§8.2)");
        assertEquals(2, mobile.variants().size(),
                "stored variants ride the snapshot for observability (per-class ordering)");

        ElementSnapshot unknown = dispatchSnapshot.assemble(projectId, 1,
                classAction("tv")).anchors().elements().get("移动登录按钮").get(0);
        assertEquals("#login", unknown.anchors().get(0).value(),
                "an unmatched class falls back to the top-level any-viewport set");

        ElementSnapshot plain = dispatchSnapshot.assemble(projectId, 1,
                clickAction("\"{{ELEM:移动登录按钮}}\"")).anchors()
                .elements().get("移动登录按钮").get(0);
        assertEquals("#login", plain.anchors().get(0).value(),
                "no declaration = legacy dispatch shape, top-level anchors (zero drift)");
        assertEquals(2, plain.variants().size());
    }

    private String classAction(String viewportClass) {
        return "{\"elementAnchors\":{\"viewportClass\":\"" + viewportClass + "\"},"
                + "\"setup\":[],\"steps\":[{\"action\":\"click\",\"target\":\"{{ELEM:移动登录按钮}}\"}]}";
    }
}
