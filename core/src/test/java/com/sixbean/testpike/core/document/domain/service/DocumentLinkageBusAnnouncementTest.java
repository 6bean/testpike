package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType;
import com.sixbean.testpike.core.linkage.support.BusObservationFixture;
import com.sixbean.testpike.core.linkage.support.ObjectChangedEventQueueProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Document member of the change-linkage bus (AN batch package A:
 * documents onto the bus). Real RabbitMQ round trip against the slot services,
 * no mocks — same posture as ObjectChangedEventPublisherTest, and the edge
 * assertions walk the seeded production ledger rather than test-local copies,
 * so what passes here is what the bus actually runs in the field.
 * <p>Semantics under test (design section 3.4 "the bus, not scattered hooks",
 * and section 3.3 "reuse, never a third detector"):
 * <ul>
 * <li>a successfully parsed upload announces {@code doc.changed} exactly
 * once with the full envelope (id/project/operator travel);</li>
 * <li>a failed parse announces nothing — a change fact only exists for
 * committed, usable content (afterCommit + success-gate discipline);</li>
 * <li>the doc fan is registered as data: the analysis queue row itself is
 * exempt (the lineage extractor already produces it in-transaction, so
 * the bus must not re-derive it), the requirement edge runs as SQL over
 * the extractor's own output instead of a third detector, and the
 * extraction-ledger internal tables are registered as extractor-owned
 * rows alongside them.</li>
 * </ul>
 * Fixtures are owned rows only (run-suffixed names, deleted by id in
 * teardown); the bound queue is exclusive and auto-delete so unrelated bus
 * traffic can never leak into these assertions.
 */
@SpringBootTest(classes = CoreApplication.class)
class DocumentLinkageBusAnnouncementTest {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private BusObservationFixture bus;

    @Autowired
    private com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder configCacheHolder;

    private JdbcTemplate jdbc;

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path storageRoot;

    private String originalLocalRoot;

    /**
     * The slot ships provider=local with an empty storage.local-root (upload
     * would die in markFailed before any change fact exists). The fixture
     * points the key at a run-owned temp dir and restores the ledger row in
     * @AfterEach — same snapshot/restore posture as StorageConfigServiceTest.
     */
    @BeforeEach
    void pinLocalStorageRoot() {
        jdbc = bus.jdbc;
        originalLocalRoot = jdbc.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = 'storage.local-root'",
                String.class);
        jdbc.update("UPDATE tp_sys_config SET config_value = ? WHERE config_key = 'storage.local-root'",
                storageRoot.toString());
        configCacheHolder.loadAll(jdbc);
    }

    @AfterEach
    void restoreLocalStorageRoot() {
        jdbc.update("UPDATE tp_sys_config SET config_value = ? WHERE config_key = 'storage.local-root'",
                originalLocalRoot);
        configCacheHolder.loadAll(jdbc);
    }

    private ObjectChangedEventQueueProbe busProbe() {
        return bus.probe();
    }

    private long newProject() {
        return jdbc.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, "lnk-doc-" + System.nanoTime(), "lkd" + System.nanoTime());
    }

    @Test
    void parsed_upload_announces_doc_changed_on_the_bus() {
        Queue queue = busProbe().bindRoutingKey(ObjectChangedEventType.DOC.routingKey());
        long projectId = newProject();
        try {
            DocumentInfo uploaded = documentService.upload(1L, projectId,
                    "联动需求文档.md",
                    "# 章节\n系统应支持按项目隔离的联动影响账查询。".getBytes(StandardCharsets.UTF_8),
                    null);
            assertNotNull(uploaded.id());

            ObjectChangedEventMessage evt = busProbe().await(queue, 5000);
            assertNotNull(evt, "a committed parse-success upload must announce doc.changed");
            assertEquals(ObjectChangedEventType.DOC.name(), evt.eventType());
            assertEquals(uploaded.id(), evt.objectId());
            assertEquals(projectId, evt.projectId(), "projectId travels (H1 isolation)");
            assertEquals(1L, evt.operatorId());
            assertNotNull(evt.objectVersion(),
                    "the post-write version rides along as the consumer dedupe anchor");
            assertNull(evt.changedFields(),
                    "null diff = arm every doc edge (upload has no field-level diff to report)");
        } finally {
            jdbc.update("DELETE FROM tp_document WHERE project_id = ?", projectId);
            jdbc.update("DELETE FROM tp_project WHERE id = ?", projectId);
        }
    }

    @Test
    void failed_parse_announces_nothing() {
        Queue queue = busProbe().bindRoutingKey(ObjectChangedEventType.DOC.routingKey());
        long projectId = newProject();
        try {
            DocumentInfo failed = documentService.upload(1L, projectId,
                    "空白文档.md", "   \n  ".getBytes(StandardCharsets.UTF_8), null);
            // Parse produced no chunks: markFailed terminal row, no change fact.
            assertNull(busProbe().await(queue, 1500),
                    "afterCommit discipline: an upload that produced no usable content "
                            + "must never arm downstream doc edges");
            assertNotNull(failed.id());
        } finally {
            jdbc.update("DELETE FROM tp_document WHERE project_id = ?", projectId);
            jdbc.update("DELETE FROM tp_project WHERE id = ?", projectId);
        }
    }

    @Test
    void doc_fan_is_registered_as_ledger_data_consuming_the_extractor_output() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT edge_key, downstream_object_type, detector_kind, detector_ref, "
                        + "disposition_strategy, risk_level, exempt "
                        + "FROM tp_change_linkage WHERE upstream_object_type = 'doc' "
                        + "ORDER BY edge_key");
        // The shipped registry grew past the original pair: the extraction
        // ledger internals (chunk / preview / ledger / stage / fidelity)
        // joined as extractor-owned rows, so the doc fan counts 7 edges.
        assertEquals(7, rows.size(),
                "doc fan = analysis-queue exempt row + requirement SQL edge row + extraction-ledger internals, no more");

        Map<String, Object> exemptRow = rows.stream()
                .filter(r -> "doc->doc_change_analysis".equals(r.get("edge_key")))
                .findFirst().orElseThrow();
        assertEquals(3, ((Number) exemptRow.get("detector_kind")).intValue(),
                "exempt rows carry no detector (kind 3 = no fan, same shape as V514 exempts)");
        assertEquals(((Number) exemptRow.get("exempt")).intValue(), 1);

        Map<String, Object> edge = rows.stream()
                .filter(r -> "doc->requirement".equals(r.get("edge_key")))
                .findFirst().orElseThrow();
        assertEquals("requirement", String.valueOf(edge.get("downstream_object_type")));
        assertEquals(2, ((Number) edge.get("detector_kind")).intValue(),
                "the requirement edge runs as SQL text, not a new bean detector");
        String ref = String.valueOf(edge.get("detector_ref"));
        assertTrue(ref.contains("tp_doc_change_analysis"),
                "the SQL must read the existing extractor output, never re-run section diffing: " + ref);
        assertTrue(ref.contains(":objectId"),
                "SQL detector uses the shared :objectId bind parameter: " + ref);
        assertEquals("grouped_draft", String.valueOf(edge.get("disposition_strategy")),
                "content change downstream = grouped draft with the one-click human accept");
        assertEquals(2, ((Number) edge.get("risk_level")).intValue());
    }
}
