package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Enrichment anchor stamped at the AI-confirm ingress (MJ provenance closure):
 * a bulk spec-enrich job's drafts, once human-ticked and confirmed, land with
 * enriched_by = the job anchor, while ordinary nl-stub confirms keep the
 * column NULL (they are AI-born, not AI-improved — the badge pair stays
 * honest). The two-click gate itself is untouched: preview-status refusal and
 * empty-selection refusal still fire before anything is written, and birth
 * facts (source/sourceRef) stay server-enforced exactly as before.
 * <p>All against the real dev database (SpringBootTest, zero mocks —
 * MockStubEnrichmentProvenanceTest posture).
 */
@SpringBootTest(classes = CoreApplication.class)
class MockAiJobConfirmEnrichmentProvenanceTest {

    private static final String TYPE_SPEC_ENRICH = "spec-enrich";
    private static final String TYPE_NL_STUB = "nl-stub";

    @Autowired
    private MockAiJobService aiJobService;

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newProject() {
        String code = "enrc" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private long newSpace(long projectId) {
        String key = "enrj-" + UUID.randomUUID().toString().substring(0, 8);
        return spaceService.create(projectId, new MockSpaceInput(
                "Space " + key, key, null, null, "prefix", "none", null, null, 100, null));
    }

    /** Job row parked in preview status — the only status the confirm door opens on. */
    private long previewJob(long spaceId, String jobType) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_mock_ai_job (space_id, job_type, status, result_preview_ref)"
                        + " VALUES (?, ?, 'preview', 'drafts/test') RETURNING id",
                Long.class, spaceId, jobType);
    }

    private MockStubInput draft(String path) {
        return new MockStubInput("rule-" + path, 10, "GET", "exact", path,
                "[]", "{}", null, null, null, "manual", null, true);
    }

    @Test
    void specEnrichConfirmStampsEnrichmentAnchorThroughTheSameTwoClickDoor() {
        long spaceId = newSpace(newProject());
        long jobId = previewJob(spaceId, TYPE_SPEC_ENRICH);

        List<MockStubInfo> landed = aiJobService.confirm(jobId, List.of(draft("/enrich-confirm/stamped")));

        assertEquals(1, landed.size());
        assertEquals("job:" + jobId, landed.get(0).enrichedBy());
        assertEquals("job:" + jobId, landed.get(0).sourceRef(), "birth anchor stays server-enforced");
        assertEquals("nl-ai", landed.get(0).source());
        assertEquals("job:" + jobId, jdbcTemplate.queryForObject(
                "SELECT enriched_by FROM tp_mock_stub WHERE id = ?", String.class, landed.get(0).id()));
    }

    @Test
    void nlStubConfirmKeepsEnrichmentColumnNull() {
        long spaceId = newSpace(newProject());
        long jobId = previewJob(spaceId, TYPE_NL_STUB);

        List<MockStubInfo> landed = aiJobService.confirm(jobId, List.of(draft("/enrich-confirm/plain")));

        assertNull(landed.get(0).enrichedBy(),
                "AI-born rules carry no improvement anchor — only enrichment jobs stamp it");
    }
}
