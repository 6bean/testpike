package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.document.api.dto.ChunkContext;
import com.sixbean.testpike.core.document.api.dto.ChunkNeighbor;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.support.SysConfigSwitchFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WR batch segment B: the extraction preview's source viewer must show why a
 * thin chunk reads as nothing (adjacent original text, not just the 13-character
 * sliver) and must be able to re-extract exactly that chunk through the same
 * pipeline door as a full-document run.
 *
 * <p>Covers the context door (preceding neighbours + position marker + the
 * hot-updatable budget key) and the chunk-level directed supplement dispatch
 * (closed validation before any status drift, chunk ids carried on the real
 * MQ payload). Contract tests run against the real slot database and the real
 * broker; no business call is mocked.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class DocumentExtractionChunkContextTest {

    private static final String CONTEXT_CHARS_KEY = "ai.doc.chunk-context-chars";

    @Autowired
    private DocumentService documentService;
    @Autowired
    private DocumentExtractionService extractionService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConfigService configService;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private MessageConverter caseEventMessageConverter;

    private final List<Long> createdProjectIds = new java.util.ArrayList<>();
    private final List<Long> createdDocIds = new java.util.ArrayList<>();
    private final List<String> createdQueues = new java.util.ArrayList<>();

    @BeforeEach
    @AfterEach
    void purgeOwnedFixtures() {
        if (!createdDocIds.isEmpty()) {
            // Chunks ride the FK ON DELETE CASCADE; removing the rows here
            // keeps the sweep independent of the (not yet wired) context door.
            // JdbcTemplate cannot bind a Java array to PG `= ANY(?)`, so the
            // ids travel as an explicit IN-list (all self-generated longs).
            String placeholders = createdDocIds.stream().map(x -> "?")
                    .collect(java.util.stream.Collectors.joining(","));
            jdbcTemplate.update("DELETE FROM tp_document WHERE id IN (" + placeholders + ")",
                    createdDocIds.toArray());
            createdDocIds.clear();
        }
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, createdProjectIds);
        createdProjectIds.clear();
        jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key = ?", CONTEXT_CHARS_KEY);
    }

    @AfterEach
    void dropTempQueues() {
        createdQueues.forEach(amqpAdmin::deleteQueue);
        createdQueues.clear();
    }

    // ==================== context door ====================

    @Test
    void chunkContextReturnsThreePrecedingNeighboursWithPositionAndChars() {
        long projectId = newProject();
        long docId = newDocument(projectId);
        long[] ids = newChunks(docId, 5, "普通段落正文");

        ChunkContext view = documentService.chunkContext(docId, ids[3]);

        assertEquals(ids[3], view.chunkId());
        assertEquals(4, view.positionIndex(), "本块序位标记=文档内第 4 块（1 基）");
        assertEquals("普通段落正文".length(), view.contentChars());
        assertEquals(List.of(ids[0], ids[1], ids[2]),
                view.preceding().stream().map(ChunkNeighbor::id).toList(),
                "同文档按 seq 前 3 邻块，正序");
        assertEquals(List.of(1, 2, 3),
                view.preceding().stream().map(ChunkNeighbor::seq).toList());
    }

    @Test
    void chunkContextDropsFarthestNeighboursOverBudgetKey() {
        long projectId = newProject();
        long docId = newDocument(projectId);
        String fat = "字".repeat(500);
        long[] ids = newChunks(docId, 5, fat);
        SysConfigSwitchFixture.upsert(jdbcTemplate, configService, CONTEXT_CHARS_KEY,
                "1000", "doc", "string",
                "回看上下文预算字数：邻块正文合计上限，超限从最远邻块开始舍弃");

        ChunkContext view = documentService.chunkContext(docId, ids[4]);

        long neighbourChars = view.preceding().stream()
                .mapToLong(n -> n.content().length()).sum();
        assertTrue(neighbourChars <= 1000,
                "邻块正文合计不得越过热更预算（当前 " + neighbourChars + "）");
        assertTrue(containsId(view.preceding(), ids[3]), "最近邻块必在（预算内优先保留近块）");
        assertTrue(!containsId(view.preceding(), ids[0])
                        && !containsId(view.preceding(), ids[1]),
                "超预算时从最远邻块开始舍弃");
    }

    // ==================== chunk-level directed supplement ====================

    @Test
    void extractRejectsChunkIdsForeignToDocumentWithoutStatusDrift() {
        long projectId = newProject();
        long docId = newDocument(projectId);
        long foreignDoc = newDocument(newProject());
        long foreignChunk = newChunks(foreignDoc, 1, "他文档的块")[0];

        ServiceException refused = assertThrows(ServiceException.class,
                () -> extractionService.extract(1L, docId, null, null, false,
                        List.of(foreignChunk)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, refused.getCode());
        assertTrue(refused.getMessage().contains(String.valueOf(foreignChunk)),
                "拒件必须点名越界块 id，用户才知道改哪");
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_document WHERE id=?", Integer.class, docId);
        assertEquals(1, status, "校验先于状态推进：拒后不得残留提取中态");
    }

    @Test
    void extractCarriesChunkIdsOnTheRealDispatchMessage() {
        long projectId = newProject();
        long docId = newDocument(projectId);
        long[] ids = newChunks(docId, 3, "待补提原文");
        String queueName = "test.chunk-ids." + UUID.randomUUID().toString().substring(0, 8);
        Queue queue = new Queue(queueName, false, true, true);
        amqpAdmin.declareQueue(queue);
        createdQueues.add(queueName);
        amqpAdmin.declareBinding(new Binding(queueName, Binding.DestinationType.QUEUE,
                RabbitEventConfig.EXCHANGE, AiGenPublisher.ROUTING_DOC_EXTRACT, null));

        extractionService.extract(1L, docId, null, null, false, List.of(ids[1]));

        var received = rabbitTemplate.receive(queueName, 5000);
        assertNotNull(received, "定向补提必须经 ai.doc.extract 路由键真实入队");
        AiDocExtractMessage msg = (AiDocExtractMessage) caseEventMessageConverter
                .fromMessage(received);
        assertEquals(docId, msg.documentId());
        assertEquals(List.of(ids[1]), msg.chunkIds(),
                "块白名单随派发下达，消费端据此只对指定块跑一轮");
    }

    // ==================== fixture helpers (self-built, swept by id) ====================

    private boolean containsId(List<ChunkNeighbor> neighbours, long id) {
        return neighbours.stream().anyMatch(n -> n.id() == id);
    }

    private long newProject() {
        long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project(name, code, created_by) VALUES(?,?,1)"
                        + " RETURNING id",
                Long.class, "chunkctx-" + UUID.randomUUID().toString().substring(0, 8),
                "ctx-" + UUID.randomUUID().toString().substring(0, 8));
        createdProjectIds.add(id);
        return id;
    }

    private long newDocument(long projectId) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_document(project_id, name, file_hash, status, created_by)"
                        + " VALUES(?,?,?,1,1) RETURNING id",
                Long.class, projectId, "chunkctx-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID().toString().replace("-", "").repeat(2).substring(0, 64));
        createdDocIds.add(id);
        return id;
    }

    private long[] newChunks(long documentId, int count, String content) {
        long[] ids = new long[count];
        for (int seq = 1; seq <= count; seq++) {
            ids[seq - 1] = jdbcTemplate.queryForObject(
                    "INSERT INTO tp_document_chunk(document_id, seq, chapter, content)"
                            + " VALUES(?,?,?,?) RETURNING id",
                    Long.class, documentId, seq, "第" + seq + "节", content);
        }
        return ids;
    }
}
