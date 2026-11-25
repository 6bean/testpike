package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {
        "testpike.scheduling.enabled=false"})
class DocumentServiceTest {

    private static final String MAX_SIZE_KEY = "ai.doc.max-size-mb";

    @Autowired
    private DocumentService documentService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ConfigService configService;
    @Autowired
    private ArtifactStorage artifactStorage;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private MessageConverter kbMessageConverter;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String code;

    private final List<Long> fixtureProjectIds = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Fixture-scoped teardown (R37): only this suite's own project subtrees.
        // The blanket TRUNCATE wiped concurrent chains' rows (r13 postmortem);
        // tp_audit_log and tp_doc_change_analysis ride the sweep. Audit
        // assertions still filter by object_type=document to block crosstalk.
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, fixtureProjectIds);
        fixtureProjectIds.clear();
    }

    private long createProject() {
        code = UUID.randomUUID().toString().substring(0, 8);
        long id = projectService.create(1L,
                new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
        fixtureProjectIds.add(id);
        return id;
    }

    private static byte[] md(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    
    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest(content)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String headingDoc() {
        return headingDoc(null);
    }

    
    private static String headingDoc(String tag) {
        StringBuilder sb = new StringBuilder("# 订单中心 PRD"
                + (tag == null ? "" : "（" + tag + "）") + "\n\n");
        for (String chapter : List.of("下单", "支付", "库存", "促销", "会员")) {
            sb.append("## ").append(chapter).append("流程\n\n");
            sb.append(chapter).append("章节正文。").append("流程细节说明文字。".repeat(60)).append("\n\n");
        }
        return sb.toString();
    }

    @Test
    void test_f1_upload_markdown_chunks_dedup_hash_and_s3_original() {
        long projectId = createProject();
        byte[] content = md(headingDoc());
        DocumentInfo info = documentService.upload(1L, projectId, "订单PRD.md", content, null);

        assertEquals(1, info.status(), "解析成功应置 status=1");
        assertEquals("订单PRD", info.name());
        assertEquals("markdown", info.fileType());
        assertEquals(content.length, info.sizeBytes());
        assertEquals(sha256(content), info.fileHash(), "file_hash=sha256(content) 十六进制小写");
        assertTrue(info.chunkCount() > 0, "TC-F1-01：分块数>0");

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_document WHERE project_id=? AND id=?",
                Integer.class, projectId, info.id());
        assertEquals(1, rows);
        List<Integer> seqs = jdbcTemplate.queryForList(
                "SELECT seq FROM tp_document_chunk WHERE document_id=? ORDER BY seq",
                Integer.class, info.id());
        assertEquals(info.chunkCount(), seqs.size(), "chunk_count 与分块行数一致");
        for (int i = 0; i < seqs.size(); i++) {
            assertEquals(i + 1, seqs.get(i), "seq 必须 1..N 连续无空洞");
        }

        Integer titled = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_document_chunk WHERE document_id=? "
                        + "AND chapter IS NOT NULL AND chapter <> ''",
                Integer.class, info.id());
        assertTrue(titled != null && titled > 0, "按标题分块应产生章节归属");

        assertNotNull(info.storageKey());
        assertTrue(artifactStorage.exists(info.storageKey()), "原件必须已入 S3");

        ServiceException dup = assertThrows(ServiceException.class,
                () -> documentService.upload(1L, projectId, "订单PRD.markdown", content, null));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, dup.getCode(), "409 人话走 RESOURCE_CONFLICT");
        assertTrue(dup.getMessage().contains("已存在"),
                "重复提示人话「已存在」，实际=" + dup.getMessage());
        Long docRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_document WHERE project_id=?", Long.class, projectId);
        assertEquals(1L, docRows, "重复上传唯一键兜底=本夹具项目单行（IT-DOC-01 组域；"
                + "计数域=夹具项目，R37 出厂基线常驻后全表口径作废）");
    }

    @Test
    void test_f1_type_whitelist_rejects_non_pdf_md() {
        long projectId = createProject();
        ServiceException exe = assertThrows(ServiceException.class,
                () -> documentService.upload(1L, projectId, "tool.exe", md("MZbinary"), null));
        assertEquals(BizErrorCode.VALIDATION_ERROR, exe.getCode());
        assertTrue(exe.getMessage().contains("类型"), "人话点名类型，实际=" + exe.getMessage());

        ServiceException txt = assertThrows(ServiceException.class,
                () -> documentService.upload(1L, projectId, "note.txt", md("hello"), null));
        assertEquals(BizErrorCode.VALIDATION_ERROR, txt.getCode());

        ServiceException noExt = assertThrows(ServiceException.class,
                () -> documentService.upload(1L, projectId, "README", md("hello"), null));
        assertEquals(BizErrorCode.VALIDATION_ERROR, noExt.getCode());

        Long docRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_document WHERE project_id=?", Long.class, projectId);
        assertEquals(0L, docRows, "白名单拒绝不得在本夹具项目留痕入库（计数域=项目，R37 基线常驻）");
    }

    @Test
    void test_f1_size_limit_from_config_hot_update() {
        long projectId = createProject();
        ConfigInfo key = configService.list("系统").stream()
                .filter(c -> MAX_SIZE_KEY.equals(c.configKey())).findFirst()
                .orElseGet(() -> configService.create(1L, new ConfigInput(MAX_SIZE_KEY, "50",
                        "系统", "int", 0, "F1 文档上传大小上限 MB（超限 400 人话，02 §3.3；V26）")));
        configService.update(1L, key.id(), new ConfigInput(MAX_SIZE_KEY, "1",
                key.groupName(), key.valueType(), 0, key.description()));
        try {
            byte[] ok = md("# 小文档\n\n正文。".repeat(1) + "填充内容用于逼近阈值。".repeat(30_000));
            assertTrue(ok.length < 1024 * 1024, "构造应 <1MB，实际=" + ok.length);
            DocumentInfo info = documentService.upload(1L, projectId, "ok.md", ok, null);
            assertEquals(1, info.status());

            byte[] tooBig = md("超限内容。".repeat(400_000));
            assertTrue(tooBig.length > 1024 * 1024, "构造应 >1MB，实际=" + tooBig.length);
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> documentService.upload(1L, projectId, "big.md", tooBig, null));
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("1"),
                    "人话应点名当前上限值，实际=" + ex.getMessage());
        } finally {
            configService.update(1L, key.id(), new ConfigInput(MAX_SIZE_KEY, "50",
                    key.groupName(), key.valueType(), 0, key.description()));
        }
    }

    @Test
    void test_f1_no_heading_sequential_chunks_single_para_resplit() {
        long projectId = createProject();
        String para1 = "第一段内容。".repeat(50);
        String monster = "超长单段正文".repeat(2_000);
        String para3 = "第三段内容。".repeat(10);
        byte[] content = md(para1 + "\n\n" + monster + "\n\n" + para3);
        DocumentInfo info = documentService.upload(1L, projectId, "无标题记录.md", content, null);

        assertEquals(1, info.status());
        assertTrue(info.chunkCount() >= 2, "顺序分块+段内再切应多块，实际=" + info.chunkCount());

        List<String> texts = jdbcTemplate.queryForList(
                "SELECT content FROM tp_document_chunk WHERE document_id=? ORDER BY seq",
                String.class, info.id());
        assertEquals(info.chunkCount(), texts.size());
        String joined = String.join("", texts);
        assertTrue(joined.contains(para1), "首段文本必须完整保留");
        assertTrue(joined.contains("超长单段正文"), "超预算段必须段内再切保留");
        assertTrue(joined.contains(para3), "尾段文本必须完整保留");
        assertFalse(texts.stream().anyMatch(t -> t.isBlank()), "不得产生空块");
    }

    
    @Test
    void test_f1_upload_pdf_real_file_chunks_and_s3_original() throws Exception {
        long projectId = createProject();
        byte[] pdf = buildPdfWithChinese();
        DocumentInfo info = documentService.upload(1L, projectId, "订单PRD.pdf", pdf, null);

        assertEquals("pdf", info.fileType());
        assertEquals(1, info.status(), "PDF 解析必须成功，errorMsg=" + info.errorMsg());
        assertEquals(sha256(pdf), info.fileHash());
        assertEquals(pdf.length, info.sizeBytes());
        assertTrue(info.chunkCount() >= 2, "长文档应顺序分块多块，实际=" + info.chunkCount());
        assertTrue(info.storageKey().endsWith(".pdf"), "原件键=pdf 扩展名");
        assertTrue(artifactStorage.exists(info.storageKey()), "S3 原件落位");

        List<String> texts = jdbcTemplate.queryForList(
                "SELECT content FROM tp_document_chunk WHERE document_id=? ORDER BY seq",
                String.class, info.id());
        String joined = String.join("", texts).replaceAll("\\s+", "");
        assertTrue(joined.contains("订单中心需求文档"), "标题句必须被抽取");
        assertTrue(joined.contains("支付流程章节正文"), "正文章句必须被抽取，实际=" 
                + joined.substring(0, Math.min(120, joined.length())));
        assertFalse(texts.stream().anyMatch(t -> t.isBlank()), "不得产生空块");

        ServiceException dup = assertThrows(ServiceException.class,
                () -> documentService.upload(1L, projectId, "订单PRD.pdf", pdf, null));
        assertTrue(dup.getMessage().contains("已存在"));
    }

    
    @Test
    void test_f1_invalid_pdf_marks_failed_with_human_message() {
        long projectId = createProject();
        byte[] garbage = "%PDF-1.4\nbroken junk not a real pdf".getBytes(StandardCharsets.UTF_8);
        DocumentInfo info = documentService.upload(1L, projectId, "坏文件.pdf", garbage, null);

        assertEquals(2, info.status(), "非法 PDF 必须置失败态");
        assertNotNull(info.errorMsg(), "失败必须带人话原因");
        assertEquals(0, info.chunkCount(), "失败不得产生分块");
    }

    
    private static byte[] buildPdfWithChinese() throws Exception {
        StringBuilder chapter = new StringBuilder("支付流程章节正文。".repeat(80));
        List<String> lines = List.of("订单中心需求文档", chapter.toString(),
                "库存与促销补充说明。".repeat(60));
        // Load the CJK glyph source from the classpath asset that production
        // already ships (same resource as PdfHtmlRenderer), so the fixture
        // works on fresh CI runners without any host-installed font package.
        try (org.apache.fontbox.ttf.TrueTypeFont ttf =
                     new org.apache.fontbox.ttf.TTFParser().parse(
                             new org.springframework.core.io.ClassPathResource(
                                     "fonts/NotoSansSC.ttf").getInputStream());
             PDDocument doc = new PDDocument()) {
            PDType0Font font = PDType0Font.load(doc, ttf, true);
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = 780;
                for (String line : lines) {
                    for (int from = 0; from < line.length() && y > 40; from += 30) {
                        cs.beginText();
                        cs.setFont(font, 11);
                        cs.newLineAtOffset(40, y);
                        cs.showText(line.substring(from, Math.min(line.length(), from + 30)));
                        cs.endText();
                        y -= 15;
                    }
                    if (y <= 40) {
                        break;
                    }
                }
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    
    @Test
    void test_f1_get_detail_and_404_human() {
        long projectId = createProject();
        DocumentInfo info = documentService.upload(1L, projectId, "订单PRD.md", md(headingDoc()), null);

        DocumentInfo detail = documentService.get(info.id());
        assertEquals(info.id(), detail.id());
        assertEquals(projectId, detail.projectId());
        assertEquals(1, detail.status());
        assertEquals(info.chunkCount(), detail.chunkCount(), "详情分块统计与上传结果一致");
        assertEquals(info.fileHash(), detail.fileHash());

        ServiceException nf = assertThrows(ServiceException.class,
                () -> documentService.get(999_999_999L));
        assertEquals(BizErrorCode.NOT_FOUND, nf.getCode());
        assertTrue(nf.getMessage().contains("不存在"), "404 人话，实际=" + nf.getMessage());
    }

    
    @Test
    void test_f1_list_project_isolation_status_keyword_paging() {
        long projectA = createProject();
        long projectB = createProject();
        DocumentInfo a1 = documentService.upload(1L, projectA, "_alpha需求.md", md(headingDoc("alpha")), null);
        documentService.upload(1L, projectA, "beta说明.md", md(headingDoc("beta")), null);
        documentService.upload(1L, projectB, "gamma.md", md(headingDoc("gamma")), null);
        DocumentInfo failed = documentService.upload(1L, projectA, "zeta_空.md", md("   \n  "), null);
        assertEquals(2, failed.status(), "构造前置：空内容应产生失败态样本");

        PageResult<DocumentInfo> listA = documentService.list(projectA, null, null, 1, 10);
        assertEquals(3L, listA.total(), "A 项目应有 3 条（2 成功+1 失败）");
        assertTrue(listA.list().stream().allMatch(d -> d.projectId() == projectA),
                "列表必须全部归属请求项目");
        assertTrue(listA.list().stream().noneMatch(d -> d.projectId() == projectB));

        PageResult<DocumentInfo> okOnly = documentService.list(projectA, 1, null, 1, 10);
        assertEquals(2L, okOnly.total());
        assertTrue(okOnly.list().stream().allMatch(d -> d.status() == 1));

        PageResult<DocumentInfo> kw = documentService.list(projectA, null, "alpha", 1, 10);
        assertEquals(1L, kw.total());
        assertEquals(a1.id(), kw.list().get(0).id());

        PageResult<DocumentInfo> p1 = documentService.list(projectA, null, null, 1, 2);
        assertEquals(2, p1.list().size());
        assertEquals(3L, p1.total());
        PageResult<DocumentInfo> p2 = documentService.list(projectA, null, null, 2, 2);
        assertEquals(1, p2.list().size());
    }

    @Test
    void test_f1_delete_removes_row_chunks_and_s3_original() {
        long projectId = createProject();
        DocumentInfo info = documentService.upload(1L, projectId, "待删.md", md(headingDoc()), null);
        String storageKey = info.storageKey();
        assertTrue(artifactStorage.exists(storageKey));

        documentService.delete(1L, info.id());

        Long docRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_document WHERE id=?", Long.class, info.id());
        assertEquals(0L, docRows, "文档行已删");
        Long chunkRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_document_chunk WHERE document_id=?", Long.class, info.id());
        assertEquals(0L, chunkRows, "分块 FK CASCADE 随删");
        assertFalse(artifactStorage.exists(storageKey), "IT-DOC-05：S3 原件随删");
    }


    
    private long auditCount(String action, String documentId) {
        // Anchor on object_id = the fixture document's own id (R37 discipline):
        // the aspect records project_id only from the request context, absent in
        // L2, so a project filter would read 0. Asserting this document's trace
        // is both environment-safe and behavior-exact.
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log"
                        + " WHERE object_type = 'document' AND action = ? AND object_id = ?",
                Long.class, action, documentId);
        return n == null ? 0L : n;
    }

    
    @Test
    void test_f1_audit_upload_success_row() {
        long projectId = createProject();
        byte[] content = md(headingDoc("audit"));
        DocumentInfo info = documentService.upload(99L, projectId, "审计PRD.md", content, null);

        assertEquals(1L, auditCount("doc_upload", String.valueOf(info.id())), "上传成功必须留审计行");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT operator_id, object_id, after_json->'value'->>'fileHash' AS after_hash,"
                        + " after_json->'value'->>'chunkCount' AS after_chunks"
                        + " FROM tp_audit_log WHERE object_type='document' AND action='doc_upload'"
                        + " AND object_id = ?", String.valueOf(info.id()));
        assertEquals(99L, ((Number) row.get("operator_id")).longValue(),
                "操作人=方法参数 operatorId（T101 回退语义）");
        assertEquals(String.valueOf(info.id()), row.get("object_id"), "object_id=文档 id");
        assertEquals(info.fileHash(), row.get("after_hash"), "after 含 fileHash 可追溯");
        assertEquals(String.valueOf(info.chunkCount()), row.get("after_chunks"), "after 含分块数");
    }

    
    @Test
    void test_f1_audit_delete_row_with_before_name() {
        long projectId = createProject();
        DocumentInfo info = documentService.upload(99L, projectId, "待删审计.md", md(headingDoc("del")), null);
        documentService.delete(99L, info.id());

        assertEquals(1L, auditCount("doc_delete", String.valueOf(info.id())), "删除必须留审计行");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT operator_id, object_id, before_json::text AS before_text"
                        + " FROM tp_audit_log WHERE object_type='document' AND action='doc_delete'"
                        + " AND object_id = ?", String.valueOf(info.id()));
        assertEquals(99L, ((Number) row.get("operator_id")).longValue());
        assertEquals(String.valueOf(info.id()), row.get("object_id"));
        assertTrue(((String) row.get("before_text")).contains("待删审计"),
                "before 含被删文档名（删除后唯一追溯源），实际=" + row.get("before_text"));
    }

    
    @Test
    void test_f1_audit_failed_parse_still_traced() {
        long projectId = createProject();
        byte[] garbage = "%PDF-1.4\nbroken junk not a real pdf".getBytes(StandardCharsets.UTF_8);
        DocumentInfo info = documentService.upload(99L, projectId, "坏审计.pdf", garbage, null);
        assertEquals(2, info.status(), "构造前置：非法 PDF 置失败态");

        assertEquals(1L, auditCount("doc_upload", String.valueOf(info.id())), "失败态同样必须留痕（不谎报=可追溯）");
        String after = jdbcTemplate.queryForObject(
                "SELECT after_json::text FROM tp_audit_log"
                        + " WHERE object_type='document' AND action='doc_upload' AND object_id = ?",
                String.class, String.valueOf(info.id()));
        assertTrue(after.contains("解析失败"), "after 含人话失败原因，实际=" + after);
    }

    
    @Test
    void test_f2_chunks_seq_ordered_with_content_and_404() {
        long projectId = createProject();
        DocumentInfo info = documentService.upload(1L, projectId, "分块清单.md", md(headingDoc("chunks")), null);

        List<Map<String, Object>> chunks = documentService.chunks(info.id());
        assertEquals(info.chunkCount(), chunks.size(), "清单条数=分块数");
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i + 1, ((Number) chunks.get(i).get("seq")).intValue(), "seq 升序 1..N");
            assertNotNull(chunks.get(i).get("content"), "正文非空（提取输入主体）");
            assertNotNull(chunks.get(i).get("chapter"), "章节归属非空（条目 chapter 来源）");
        }
        String joined = chunks.stream().map(c -> (String) c.get("content"))
                .reduce("", String::concat);
        assertTrue(joined.contains("下单章节正文"), "首块在前");
        assertTrue(joined.contains("会员章节正文"), "末块在后");

        ServiceException nf = assertThrows(ServiceException.class,
                () -> documentService.chunks(999_999_999L));
        assertEquals(BizErrorCode.NOT_FOUND, nf.getCode());
        assertTrue(nf.getMessage().contains("不存在"), "404 人话，实际=" + nf.getMessage());
    }


    
    @Test
    void test_kb_upload_ddl_dict_persists_category_and_publishes_kb_ingest() {
        long projectId = createProject();
        String queueName = "test.doc-cat." + UUID.randomUUID().toString().substring(0, 8);
        org.springframework.amqp.core.Queue queue =
                new org.springframework.amqp.core.Queue(queueName, false, true, true);
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(new org.springframework.amqp.core.Binding(queueName,
                org.springframework.amqp.core.Binding.DestinationType.QUEUE,
                com.sixbean.testpike.core.events.domain.component.RabbitEventConfig.EXCHANGE,
                com.sixbean.testpike.core.events.domain.service.AiGenPublisher.ROUTING_KB_INGEST, null));
        try {
            DocumentInfo info = documentService.upload(1L, projectId, "订单库字典.md",
                    md(headingDoc("ddl")), "DDL_DICT");
            assertEquals("DDL_DICT", info.category(), "视图回显分类");
            String stored = jdbcTemplate.queryForObject(
                    "SELECT doc_category FROM tp_document WHERE id=?", String.class, info.id());
            assertEquals("DDL_DICT", stored, "V74 doc_category 落列");

            var received = rabbitTemplate.receive(queueName, 5000);
            assertNotNull(received, "DDL_DICT 行解析成功必须发知识库入库事件（14 §2「上传解析后」）");
            com.sixbean.testpike.common.mq.AiKbIngestMessage kb =
                    (com.sixbean.testpike.common.mq.AiKbIngestMessage)
                            kbMessageConverter.fromMessage(received);
            assertEquals(info.id(), kb.documentId());
            assertEquals(projectId, kb.projectId());
            assertEquals("DDL_DICT", kb.category(), "消息携带分类（消费端 source_type 依据）");
        } finally {
            amqpAdmin.deleteQueue(queueName);
        }
    }

    
    @Test
    void test_kb_upload_default_category_is_test_spec_and_no_kb_ingest_on_upload() {
        long projectId = createProject();
        String queueName = "test.doc-cat-neg." + UUID.randomUUID().toString().substring(0, 8);
        org.springframework.amqp.core.Queue queue =
                new org.springframework.amqp.core.Queue(queueName, false, true, true);
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(new org.springframework.amqp.core.Binding(queueName,
                org.springframework.amqp.core.Binding.DestinationType.QUEUE,
                com.sixbean.testpike.core.events.domain.component.RabbitEventConfig.EXCHANGE,
                com.sixbean.testpike.core.events.domain.service.AiGenPublisher.ROUTING_KB_INGEST, null));
        try {
            DocumentInfo info = documentService.upload(1L, projectId, "缺省分类PRD.md",
                    md(headingDoc("default-cat")), null);
            assertEquals("TEST_SPEC", info.category(), "null=TEST_SPEC 缺省（存量语义零变化）");
            assertNull(rabbitTemplate.receive(queueName, 1000),
                    "TEST_SPEC 行上传链路不发事件（触发点定案 B：仅 DDL_DICT 在解析成功后入库）");
        } finally {
            amqpAdmin.deleteQueue(queueName);
        }
    }

    
    @Test
    void test_kb_upload_illegal_category_400_human_message_no_row() {
        long projectId = createProject();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> documentService.upload(1L, projectId, "非法分类.md",
                        md(headingDoc("illegal")), "DATA_DICT"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("分类"), "人话点名分类字段，实际=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("DDL_DICT"), "人话列出合法值，实际=" + ex.getMessage());
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_document WHERE project_id=?", Long.class, projectId)
                        .longValue(), "非法即拒不插占位行（计数域=本夹具项目，R37 基线常驻）");
    }

    // ==================== page 12: server-side list sorting ====================

    /**
     * Markdown body sized by chapter count: more chapters strictly grow both
     * size_bytes and chunk_count, so seeded rows have deterministic orders
     * while file_type/status stay tied (id DESC tie-break proof).
     */
    private static String chaptersDoc(int chapters, String tag) {
        StringBuilder sb = new StringBuilder("# 排序夹具 ").append(tag).append("\n\n");
        for (int i = 0; i < chapters; i++) {
            sb.append("## 第").append(i).append("章\n\n")
                    .append("正文段落。".repeat(20)).append("\n\n");
        }
        return sb.toString();
    }

    /** Seed three uploads (6ch/-z, 2ch/-a, 4ch/-m) in one project; insertion
     * order deliberately disagrees with every column order under test. */
    private DocumentInfo[] seedSortFixture(long projectId, String marker) {
        DocumentInfo big = documentService.upload(1L, projectId, marker + "-z.md",
                md(chaptersDoc(6, marker + "z")), null);
        DocumentInfo small = documentService.upload(1L, projectId, marker + "-a.md",
                md(chaptersDoc(2, marker + "a")), null);
        DocumentInfo mid = documentService.upload(1L, projectId, marker + "-m.md",
                md(chaptersDoc(4, marker + "m")), null);
        return new DocumentInfo[]{big, small, mid};
    }

    private List<Long> idsOf(long projectId, String marker, String sortBy, String sortDir) {
        return documentService.list(projectId, null, marker, null, 1, 20, sortBy, sortDir)
                .list().stream().map(DocumentInfo::id).toList();
    }

    /** No sort key keeps the historical id DESC order and the legacy
     * five-argument call delegates to the same default (zero drift). */
    @Test
    void test_sort_absent_key_keeps_id_desc_and_legacy_overload_delegates() {
        long projectId = createProject();
        String marker = "t292b5-doc-" + code;
        DocumentInfo[] seeded = seedSortFixture(projectId, marker);
        List<Long> newestFirst = List.of(seeded[2].id(), seeded[1].id(), seeded[0].id());
        assertEquals(newestFirst, idsOf(projectId, marker, null, null));
        assertEquals(newestFirst, idsOf(projectId, marker, "", ""));
        List<Long> viaLegacy = documentService.list(projectId, null, marker, 1, 20)
                .list().stream().map(DocumentInfo::id).toList();
        assertEquals(newestFirst, viaLegacy, "legacy 5-arg overload must not drift");
    }

    /** size_bytes asc/desc mirror each other over the whole marker window
     * (6ch > 4ch > 2ch by construction). */
    @Test
    void test_sort_size_bytes_both_directions() {
        long projectId = createProject();
        String marker = "t292b5-doc-" + code;
        DocumentInfo[] seeded = seedSortFixture(projectId, marker);
        List<Long> smallFirst = List.of(seeded[1].id(), seeded[2].id(), seeded[0].id());
        assertEquals(smallFirst, idsOf(projectId, marker, "size_bytes", "asc"));
        assertEquals(smallFirst.reversed(), idsOf(projectId, marker, "size_bytes", "desc"));
        // construction guard: sizes really differ as the order claims
        assertTrue(seeded[0].sizeBytes() > seeded[2].sizeBytes()
                && seeded[2].sizeBytes() > seeded[1].sizeBytes());
    }

    /** name asc follows the -a/-m/-z suffixes; file_type is tied across all
     * three rows, so the constant id DESC tie-break keeps that order
     * deterministic. */
    @Test
    void test_sort_name_column_and_tied_file_type_pinned_by_id_desc() {
        long projectId = createProject();
        String marker = "t292b5-doc-" + code;
        DocumentInfo[] seeded = seedSortFixture(projectId, marker);
        assertEquals(List.of(seeded[1].id(), seeded[2].id(), seeded[0].id()),
                idsOf(projectId, marker, "name", "asc"));
        assertEquals(List.of(seeded[2].id(), seeded[1].id(), seeded[0].id()),
                idsOf(projectId, marker, "file_type", "asc"));
        assertEquals(List.of(seeded[2].id(), seeded[1].id(), seeded[0].id()),
                idsOf(projectId, marker, "status", "asc"),
                "all status=1 tied -> id DESC tie-break decides");
    }

    /** Sorting keeps paging consistent: page 2 continues the ordered window
     * and total stays truthful. */
    @Test
    void test_sort_applies_across_pages() {
        long projectId = createProject();
        String marker = "t292b5-doc-" + code;
        DocumentInfo[] seeded = seedSortFixture(projectId, marker);
        PageResult<DocumentInfo> first = documentService.list(
                projectId, null, marker, null, 1, 2, "size_bytes", "asc");
        PageResult<DocumentInfo> second = documentService.list(
                projectId, null, marker, null, 2, 2, "size_bytes", "asc");
        assertEquals(List.of(seeded[1].id(), seeded[2].id()),
                first.list().stream().map(DocumentInfo::id).toList());
        assertEquals(List.of(seeded[0].id()),
                second.list().stream().map(DocumentInfo::id).toList());
        assertEquals(3L, first.total());
    }

    /** An injection-shaped or off-list key is refused fail-closed with a human
     * 400 naming the whitelist; rows stay intact afterwards. */
    @Test
    void test_sort_off_list_key_refused_with_whitelist_hint() {
        long projectId = createProject();
        String marker = "t292b5-doc-" + code;
        seedSortFixture(projectId, marker);
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> idsOf(projectId, marker, "size_bytes; DROP TABLE tp_document", "asc"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, rejected.getCode());
        assertTrue(rejected.getMessage().contains("size_bytes"),
                "error should name the allowed columns, actual=" + rejected.getMessage());
        assertEquals(3, idsOf(projectId, marker, null, null).size(), "rows must be unaffected");
    }
}
