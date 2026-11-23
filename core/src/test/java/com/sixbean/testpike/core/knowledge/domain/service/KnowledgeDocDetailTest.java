package com.sixbean.testpike.core.knowledge.domain.service;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.knowledge.api.dto.KnowledgeChunkItem;
import com.sixbean.testpike.core.knowledge.api.dto.KnowledgeDocDetail;
import com.sixbean.testpike.core.knowledge.api.dto.KnowledgeHitInput;
import com.sixbean.testpike.core.knowledge.api.dto.KnowledgeHitItem;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class KnowledgeDocDetailTest {

    @Autowired
    private KnowledgeDocService knowledgeDocService;

    @Autowired
    private KnowledgeHitService knowledgeHitService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> fixtureDocIds = new ArrayList<>();

    private final List<Long> fixtureTaskIds = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanFixtureRows() {
        // R37 teardown: delete only rows this suite tracked by id. The former
        // blanket TRUNCATE tp_knowledge_doc/tp_ai_task RESTART IDENTITY CASCADE
        // wiped 51 live ledger rows and queued AI tasks of concurrent chains,
        // and rewound tp_ai_task_id_seq into stale idempotency markers
        // (generation-family postmortem, ).
        for (long docId : fixtureDocIds) {
            // knowledge.tp_knowledge_chunk carries no FK: sweep by metadata.
            jdbcTemplate.update("DELETE FROM knowledge.tp_knowledge_chunk "
                    + "WHERE metadata->>'doc_id' = ?", String.valueOf(docId));
        }
        // tp_knowledge_hit rides the FK CASCADE of the doc delete below.
        SharedDbFixtureSweep.byIds(jdbcTemplate, "tp_knowledge_doc", fixtureDocIds);
        SharedDbFixtureSweep.byIds(jdbcTemplate, "tp_ai_task", fixtureTaskIds);
        fixtureDocIds.clear();
        fixtureTaskIds.clear();
    }

    private long seedDoc(long projectId, String sourceType, String sourceRef, String title) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_knowledge_doc "
                        + "(project_id, pack_id, source_type, source_ref, title, content, status) "
                        + "VALUES (?, 0, ?, ?::bigint, ?, ?, 'ACTIVE') RETURNING id",
                Long.class, projectId, sourceType, sourceRef, title, "全文-" + title);
        fixtureDocIds.add(id);
        return id;
    }

    
    private String seedChunk(long docId, int chunkIndex, String content) {
        String chunkId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO knowledge.tp_knowledge_chunk (id, content, metadata, embedding) "
                        + "VALUES (?::uuid, ?, ?::jsonb, ('[' || repeat('0,', 1535) || '0]')::vector)",
                chunkId, content,
                "{\"doc_id\": " + docId + ", \"chunk_index\": " + chunkIndex
                        + ", \"lang\": \"zh-CN\", \"status\": \"ACTIVE\", \"title\": \"t-" + docId + "\"}");
        return chunkId;
    }

    private long seedAiTask(String requirementText) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_ai_task (project_id, operator_id, task_type, status, "
                        + "requirement_text) VALUES (1, 1, 1, 3, ?) RETURNING id",
                Long.class, requirementText);
        fixtureTaskIds.add(id);
        return id;
    }

    @Test
    void 详情_台账加chunk按index升序_hitCount与留痕一致() {
        long docId = seedDoc(11L, "TEST_SPEC", null, "spec-detail");
        String c2 = seedChunk(docId, 2, "chunk正文B");
        String c1 = seedChunk(docId, 1, "chunk正文A");
        // Noise chunk rides a tracked fixture doc (was an untracked doc_id=999
        // literal that the old blanket chunk delete silently absorbed).
        seedChunk(seedDoc(12L, "TEST_SPEC", null, "other-doc"), 1, "别的doc");

        int recorded = knowledgeHitService.record(List.of(
                new KnowledgeHitInput(docId, null, "GEN_CASE", List.of(c1, c2))));
        assertEquals(1, recorded, "一次检索聚合一行");

        KnowledgeDocDetail detail = knowledgeDocService.detail(docId);
        assertEquals("spec-detail", detail.title(), "台账字段全量透出");
        assertEquals("全文-spec-detail", detail.content(), "正文全文不截断（TC-V2-01）");
        assertEquals(1, detail.hitCount(), "被引用计数=留痕行数（一次检索聚合一行，TC-V2-01）");
        List<KnowledgeChunkItem> chunks = detail.chunks();
        assertEquals(2, chunks.size());
        assertEquals("chunk正文A", chunks.get(0).content(), "chunk_index 升序（重组可读全文）");
        assertEquals("chunk正文B", chunks.get(1).content());
        assertEquals(c1, chunks.get(0).chunkId());
        assertEquals(1, chunks.get(0).chunkIndex());
        assertEquals("zh-CN", chunks.get(0).metadata().get("lang"), "元数据十字段随行透出");
        assertEquals("ACTIVE", chunks.get(0).metadata().get("status"));
    }

    @Test
    void 详情_doc不存在_40001人话点名() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> knowledgeDocService.detail(424242L));
        assertTrue(ex.getMessage().contains("424242"), "人话点名 id：" + ex.getMessage());
    }

    @Test
    void 留痕_已删台账doc静默跳过_观测面恒ok() {
        long gone = seedDoc(11L, "TEST_CASE", "501", "to-delete");
        seedChunk(gone, 1, "x");
        int before = knowledgeHitService.record(List.of(
                new KnowledgeHitInput(gone, null, "GEN_CASE", List.of("c"))));
        assertEquals(1, before);
        jdbcTemplate.update("DELETE FROM tp_knowledge_doc WHERE id = ?", gone);
        int after = knowledgeHitService.record(List.of(
                new KnowledgeHitInput(gone, null, "GEN_CASE", List.of("c"))));
        assertEquals(0, after, "幽灵 doc 静默跳过，观测面不炸主链");
        assertTrue(knowledgeHitService.listHits(gone, 20).isEmpty());
    }

    @Test
    void 溯源列表_任务人话标题透传_task可空() {
        long docId = seedDoc(11L, "TEST_CASE", "501", "spec-hit");
        seedChunk(docId, 1, "y");
        long taskId = seedAiTask("生成提现风控用例的需求描述");
        knowledgeHitService.record(List.of(
                new KnowledgeHitInput(docId, taskId, "GEN_CASE", List.of("chunk-1")),
                new KnowledgeHitInput(docId, null, "SCRIPT_GEN", List.of("chunk-1"))));

        List<KnowledgeHitItem> hits = knowledgeHitService.listHits(docId, 20);
        assertEquals(2, hits.size());
        KnowledgeHitItem withTask = hits.stream()
                .filter(h -> h.taskId() != null).findFirst().orElseThrow();
        assertEquals(taskId, withTask.taskId());
        assertTrue(withTask.taskTitle().contains("提现风控"), "任务人话标题=LEFT JOIN tp_ai_task："
                + withTask.taskTitle());
        assertEquals("GEN_CASE", withTask.scene());
        assertEquals(List.of("chunk-1"), withTask.chunkIds(), "溯源粒度=chunk 集合透传");
        KnowledgeHitItem noTask = hits.stream()
                .filter(h -> h.taskId() == null).findFirst().orElseThrow();
        assertNull(noTask.taskTitle(), "task_id 可空=观测面可空透传");
        assertEquals("SCRIPT_GEN", noTask.scene());
    }

    @Test
    void 删台账行_级联清留痕_计数归零禁幽灵() {
        long docId = seedDoc(11L, "TEST_CASE", "501", "spec-cascade");
        seedChunk(docId, 1, "z");
        knowledgeHitService.record(List.of(
                new KnowledgeHitInput(docId, null, "GEN_CASE", List.of("c1", "c2"))));
        assertEquals(1, knowledgeDocService.detail(docId).hitCount());

        jdbcTemplate.update("DELETE FROM tp_knowledge_doc WHERE id = ?", docId);
        assertEquals(0, knowledgeHitService.listHits(docId, 20).size(),
                "FK CASCADE：删台账行留痕同灭（V85 冻结口径）");
    }
}
