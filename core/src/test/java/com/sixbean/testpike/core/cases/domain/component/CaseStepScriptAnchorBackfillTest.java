package com.sixbean.testpike.core.cases.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.domain.service.CaseServiceImpl;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Script write-back anchor chain .
 * <p>Why this gate exists: stage-2/off-site write-back lands a script whose
 * actions reference page elements through the {{ELEM:name}} dialect, but the
 * step ledger (tp_case_step.anchor) never absorbed that evidence — the live
 * window measured steps=6 anchored=0. This component backfills the anchor
 * column from the SAVED action document at the single core save door, so every
 * write chain (AI stage-2, off-site write-back, repair landing, manual script
 * edit) leaves the same per-step element evidence without a second dialect.
 * <p>Contracts pinned here: extraction honours explicit stepNo and falls back
 * to document order; merging keeps the requirement-block source evidence and
 * only upgrades the kind label when element refs arrive; replaying an identical
 * action document writes nothing (R28); the save door itself is never blocked
 * by backfill (fail-soft posture of the WE candidate ledger hook).
 * <p>Runs against the live slot database through the real service chain
 * (no mock); the class is transactional so seeded rows leave zero residue (R37).
 */
@SpringBootTest(classes = CoreApplication.class)
@Transactional
class CaseStepScriptAnchorBackfillTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CaseServiceImpl caseService;

    @Autowired
    private ScriptService scriptService;

    @Autowired
    private CaseStepScriptAnchorBackfill backfill;

    @Autowired
    private CaseStepAnchorNormalizer normalizer;

    private long projectId;

    @BeforeEach
    void seedProject() {
        projectId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 940000 FROM tp_project", Long.class);
        jdbcTemplate.update("INSERT INTO tp_project (id, name, code) OVERRIDING SYSTEM VALUE VALUES (?, ?, ?)",
                projectId, "anchor backfill project", "ANCH-BF-" + projectId);
    }

    @Test
    void refsByStepReadsExplicitAndInheritedStepNumbers() {
        String actionJson = """
                {"caseId":9,"steps":[
                  {"stepNo":1,"action":"click","target":{"locator":"{{ELEM:login-btn}}"},
                   "description":"点击 {{ELEM:login-btn}} 登录"},
                  {"stepNo":3,"action":"input","target":{"locator":"{{ELEM:user-name}}"},
                   "steps":[{"action":"assertText","target":{"locator":"{{ELEM:inner-note}}"}}]}
                ]}""";
        Map<Integer, List<String>> refs = backfill.refsByStep(actionJson);
        assertEquals(List.of("login-btn"), refs.get(1),
                "the same element named twice in one step must collapse to one ref");
        assertEquals(List.of("user-name", "inner-note"), refs.get(3),
                "nested branch steps inherit the parent stepNo (document order, no renumbering)");
        assertEquals(2, refs.size(), "steps without element refs must not appear");
    }

    @Test
    void mergeProducesCanonicalElemShapeAndKeepsSourceEvidence() {
        String fromNull = normalizer.mergeElementAnchor(null, List.of("login-btn"));
        assertNotNull(fromNull);
        assertTrue(fromNull.contains("elem") && fromNull.contains("login-btn"));

        String source = "{\"kind\":\"source\",\"requirementId\":7,\"documentId\":9,\"chunkIds\":[11,12]}";
        String merged = normalizer.mergeElementAnchor(source, List.of("submit-btn"));
        assertTrue(merged.contains("\"kind\":\"elem\"".replace("\":\"", "\": \""))
                        || (merged.contains("kind") && merged.contains("elem")),
                "a step that gains element refs is re-labelled to the element dialect");
        assertTrue(merged.contains("requirementId") && merged.contains("11"),
                "the requirement-block source evidence must survive the merge");
        assertTrue(merged.contains("submit-btn"));
    }

    @Test
    void backfillLandsOnlyChangedRowsAndStaysIdempotentOnReplay() {
        CaseInfo created = caseService.create(1L, createInput(List.of(
                new CaseStepInput(1, "打开登录页", 0, null),
                new CaseStepInput(2, "提交表单", 1,
                        "{\"kind\":\"source\",\"requirementId\":7,\"documentId\":9,\"chunkIds\":[11,12]}"),
                new CaseStepInput(3, "查看结果", 1, null))));
        String actionJson = """
                {"caseId":1,"steps":[
                  {"stepNo":1,"action":"goto","target":{"url":"https://shop.example.com/login"}},
                  {"stepNo":2,"action":"click","target":{"locator":"{{ELEM:submit-btn}}"}}
                ]}""";

        assertEquals(1, backfill.backfill(created.id(), actionJson),
                "only the step that gained element evidence is rewritten");
        List<String> anchors = anchorsOf(created.id());
        assertNull(anchors.get(0), "a script step without element refs leaves the anchor alone");
        assertTrue(anchors.get(1).contains("submit-btn") && anchors.get(1).contains("requirementId"),
                "merge keeps source evidence and adds the element ref");
        assertNull(anchors.get(2));

        LocalDateTime touchedAt = stepUpdatedAt(created.id(), 2);
        assertEquals(0, backfill.backfill(created.id(), actionJson),
                "identical replay must write nothing (R28)");
        assertEquals(touchedAt, stepUpdatedAt(created.id(), 2),
                "identical replay must not bump tp_case_step.updated_at");
    }

    @Test
    void saveDoorRunsTheBackfillHookWithoutBlockingAndWithoutRefs() {
        CaseInfo created = caseService.create(1L, createInput(List.of(
                new CaseStepInput(1, "打开登录页", 0, null))));
        scriptService.save(new ScriptSaveInput(created.id(), "v2",
                "{\"caseId\":" + created.id() + ",\"steps\":[]}", 2));
        assertNull(anchorsOf(created.id()).get(0),
                "an action document without element refs leaves the step anchor untouched");
    }

    private CaseCreateInput createInput(List<CaseStepInput> steps) {
        return new CaseCreateInput(projectId, "回写锚链用例", "前置", "预期",
                1, 2, "web", 1, null, null, null, steps);
    }

    private List<String> anchorsOf(long caseId) {
        return jdbcTemplate.queryForList(
                "SELECT anchor::text FROM tp_case_step WHERE case_id = ? ORDER BY step_no",
                String.class, caseId);
    }

    private LocalDateTime stepUpdatedAt(long caseId, int stepNo) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM tp_case_step WHERE case_id = ? AND step_no = ?",
                LocalDateTime.class, caseId, stepNo);
    }
}
