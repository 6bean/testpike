package com.sixbean.testpike.core.document.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiKbIngestMessage;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.audit.domain.component.AuditLogContext;
import com.sixbean.testpike.core.audit.domain.component.AuditSnapshot;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.document.domain.component.ChunkPiece;
import com.sixbean.testpike.core.document.domain.component.DocumentUploadedEvent;
import com.sixbean.testpike.core.document.domain.component.ExtractionSourceId;
import com.sixbean.testpike.core.document.domain.component.MarkdownChunkSplitter;
import com.sixbean.testpike.core.document.domain.service.DocumentService;
import com.sixbean.testpike.core.events.domain.component.AiTaskEventTrimSweeper;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Service
public class DocumentServiceImpl implements DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);

    
    public static final String MAX_SIZE_MB_KEY = "ai.doc.max-size-mb";
    
    private static final int DEFAULT_MAX_SIZE_MB = 50;

    /** segment B: preceding-neighbour text budget key (hot-updatable). */
    static final String CHUNK_CONTEXT_CHARS_KEY = "ai.doc.chunk-context-chars";
    /** Factory value of the context budget (V605 seed keeps this in step). */
    static final int DEFAULT_CHUNK_CONTEXT_CHARS = 4000;

    /**
 * Document categories (tp_document.doc_category value set). REFERENCE marks
 * pure-knowledge uploads: accepted at upload, started outside the
 * requirement-extraction queue; its per-document usage switches stay
 * marks the source doc requirement points and cases are derived from --
 * same extraction door as TEST_SPEC, never tightened.
 */
    public static final String CATEGORY_TEST_SPEC = "TEST_SPEC";
    public static final String CATEGORY_REQUIREMENT_DOC = "REQUIREMENT_DOC";
    public static final String CATEGORY_DDL_DICT = "DDL_DICT";
    public static final String CATEGORY_REFERENCE = "REFERENCE";
    private static final java.util.Set<String> CATEGORIES =
            java.util.Set.of(CATEGORY_TEST_SPEC, CATEGORY_REQUIREMENT_DOC,
                    CATEGORY_DDL_DICT, CATEGORY_REFERENCE);

    /**
     * Hot-update whitelist of categories whose uploads default the per-document
     * "participate in requirement extraction" switch ON (V415 seed). Shipped
     * default is an equivalence fold-in of the historical gate: ON for
     * TEST_SPEC/REQUIREMENT_DOC/DDL_DICT, OFF for REFERENCE -- adopting the
     * whitelist may never tighten what participates today (decision 9 guard).
     */
    public static final String EXTRACTION_ALLOW_CATEGORIES_KEY =
            "ai.doc.extraction-allow-categories";
    private static final String DEFAULT_EXTRACTION_ALLOW_CATEGORIES =
            CATEGORY_TEST_SPEC + "," + CATEGORY_REQUIREMENT_DOC + "," + CATEGORY_DDL_DICT;

    private static final String TYPE_MARKDOWN = "markdown";
    private static final String TYPE_PDF = "pdf";
    private static final int STATUS_PARSING = 0;
    private static final int STATUS_SUCCESS = 1;
    private static final int STATUS_FAILED = 2;

    
    private static final String DOCUMENT_COLUMNS = """
            SELECT id, project_id, name, file_type, file_hash, size_bytes, storage_key,
                   status, chunk_count, error_msg, doc_category,
                   use_for_extraction, use_in_case_prompt, use_in_grounding, revision,
                   lineage_group_id,
                   CASE WHEN lineage_group_id IS NULL THEN NULL ELSE
                        (SELECT count(*) FROM tp_document v
                          WHERE v.lineage_group_id = tp_document.lineage_group_id
                            AND v.id <= tp_document.id) END AS lineage_seq,
                   CASE WHEN lineage_group_id IS NULL THEN NULL ELSE
                        (SELECT count(*) FROM tp_document v
                          WHERE v.lineage_group_id = tp_document.lineage_group_id) END AS lineage_size
            FROM tp_document
            """;
    private static final RowMapper<DocumentInfo> MAPPER = (rs, rowNum) -> new DocumentInfo(
            rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getLong(6), rs.getString(7), rs.getInt(8), rs.getInt(9), rs.getString(10),
            rs.getString(11), rs.getBoolean(12), rs.getBoolean(13), rs.getBoolean(14),
            rs.getInt(15), rs.getObject(16, Long.class),
            ordinalOrNull(rs, 17), ordinalOrNull(rs, 18));

    /**
     * Read a nullable BIGINT aggregate as Integer. Postgres counts arrive as int8,
     * so {@code getObject(col, Integer.class)} rejects the conversion; narrow the
     * value explicitly and keep the NULL branch for rows outside a lineage group.
     */
    private static Integer ordinalOrNull(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : (int) value;
    }


    private final JdbcTemplate jdbcTemplate;
    private final ArtifactStorage artifactStorage;
    private final SystemConfigReader systemConfigReader;
    private final MarkdownChunkSplitter chunkSplitter;
    private final AiGenPublisher aiGenPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final AiTaskEventTrimSweeper eventTrimSweeper;
    private final com.sixbean.testpike.core.linkage.domain.service.ObjectChangedEventPublisher
            objectChangedEvents;
    private final com.sixbean.testpike.core.config.domain.service.ConfigService configService;

    public DocumentServiceImpl(JdbcTemplate jdbcTemplate,
                              ArtifactStorage artifactStorage,
                              SystemConfigReader systemConfigReader,
                              MarkdownChunkSplitter chunkSplitter,
                              AiGenPublisher aiGenPublisher,
                              ApplicationEventPublisher eventPublisher,
                              AiTaskEventTrimSweeper eventTrimSweeper,
                              com.sixbean.testpike.core.linkage.domain.service.ObjectChangedEventPublisher
                                      objectChangedEvents,
                              com.sixbean.testpike.core.config.domain.service.ConfigService configService) {
        this.jdbcTemplate = jdbcTemplate;
        this.artifactStorage = artifactStorage;
        this.systemConfigReader = systemConfigReader;
        this.chunkSplitter = chunkSplitter;
        this.aiGenPublisher = aiGenPublisher;
        this.eventPublisher = eventPublisher;
        this.eventTrimSweeper = eventTrimSweeper;
        this.objectChangedEvents = objectChangedEvents;
        this.configService = configService;
    }

    @Override
    @Audit(objectType = "document", action = "doc_upload", objectId = "#result.id", after = "#result")
    public DocumentInfo upload(long operatorId, long projectId, String filename, byte[] content,
                               String category) {
        assertProjectExists(projectId);
        String fileType = resolveFileType(filename);
        String docCategory = resolveCategory(category);
        assertSizeAllowed(content == null ? 0 : content.length);
        byte[] bytes = content == null ? new byte[0] : content;
        String fileHash = sha256Hex(bytes);
        String displayName = stripExtension(filename, fileType);
        Long lineageGroupId = findLineageGroupId(projectId, displayName, fileType);
        assertNotDuplicatedInGroup(fileHash, filename, lineageGroupId);

        Long docId = insertPlaceholder(projectId, displayName, fileType, fileHash,
                bytes.length, operatorId, docCategory, lineageGroupId);
        if (lineageGroupId == null) {
            // First member of a fresh lineage group: the row roots its own group.
            jdbcTemplate.update("UPDATE tp_document SET lineage_group_id = ? WHERE id = ?",
                    docId, docId);
        }
        String storageKey = "documents/" + projectId + "/" + docId
                + ("pdf".equals(fileType) ? ".pdf" : ".md");
        try {
            artifactStorage.putBytes(storageKey, bytes,
                    "pdf".equals(fileType) ? "application/pdf" : "text/markdown");
            String text = extractText(fileType, bytes);
            List<ChunkPiece> pieces = chunkSplitter.split(text);
            if (pieces.isEmpty()) {
                throw new ServiceException("未能从文档中解析出有效内容（可能为图片版 PDF 或空文件），"
                        + "建议改用含文字层的 PDF / Markdown 后，在「需求提取」页点「上传文档」重新上传",
                        BizErrorCode.VALIDATION_ERROR);
            }
            insertChunks(docId, pieces);
            DocumentInfo uploaded = markSuccess(docId, storageKey, pieces.size(),
                    projectId, operatorId, docCategory);
            if (lineageGroupId != null) {
                // Re-upload into a known lineage: pair against the group's newest
                // parsed version automatically — no manual reanalyze step.
                eventPublisher.publishEvent(new DocumentUploadedEvent(
                        uploaded.id(), projectId, operatorId, lineageGroupId));
            }
            // Linkage bus announcement: a committed parse-success upload is the
            // document's change fact. The publisher defers to afterCommit (a
            // rollback emits nothing) and never blocks the main flow. The doc
            // member has no field-level diff at upload time, so changedFields
            // stays null = arm every edge registered for doc in the ledger;
            // objectVersion is the count of parsed members this row roots or
            // joins in its lineage group (a real post-write version anchor,
            // not a fabricated constant).
            Integer lineageVersion = jdbcVersionOfLineage(projectId, uploaded.id());
            objectChangedEvents.publish(
                    new com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage(
                            com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType
                                    .DOC.name(),
                            uploaded.id(), projectId, operatorId, lineageVersion,
                            null, System.currentTimeMillis()));
            return uploaded;
        } catch (Exception e) {
            String reason = e instanceof ServiceException ? e.getMessage()
                    : "文档解析失败，请检查文件内容或格式";
            log.warn("F1 文档解析失败: docId={} file={} err={}", docId, filename, e.toString());
            return markFailed(docId, storageKey, reason);
        }
    }

    /**
     * Post-write version anchor for the doc bus announcement: how many parsed
     * members the lineage of this document now holds (the row roots its own
     * lineage on a first upload). Read over the same connection that just
     * wrote the success status, so it reflects the committed shape.
     */
    private Integer jdbcVersionOfLineage(long projectId, long docId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_document "
                        + "WHERE lineage_group_id = (SELECT lineage_group_id FROM tp_document WHERE id = ?) "
                        + "AND status = ?",
                Integer.class, docId, STATUS_SUCCESS);
        return count == null ? 1 : count;
    }

    @Override
    public DocumentInfo get(long id) {
        return jdbcTemplate.query(DOCUMENT_COLUMNS + " WHERE id = ?", MAPPER, id)
                .stream().findFirst()
                .orElseThrow(() -> new ServiceException("文档不存在（或已删除）"
                        + "，请点「刷新」回到「需求提取」列表重新选择", BizErrorCode.NOT_FOUND));
    }

    @Override
    public PageResult<DocumentInfo> list(long projectId, Integer status, String keyword,
                                         int page, int size) {
        return list(projectId, status, keyword, null, page, size, null, null);
    }

    /**
 * Sort whitelist for the document list . Values are physical
 * columns of tp_document; the map output is the only thing reaching the
 * ORDER BY clause, enforced fail-closed by the shared sort-column gate.
 * created_at stays out of the whitelist: the page renders no time column, so
 * exposing it would widen the contract without a consumer.
 */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "id",
            "name", "name",
            "file_type", "file_type",
            "size_bytes", "size_bytes",
            "chunk_count", "chunk_count",
            "doc_category", "doc_category",
            "status", "status",
            "file_hash", "file_hash");

    /**
     * ORDER BY builder for the document window: a null/blank key keeps the
     * historical id DESC default; a whitelisted key orders by that column with
     * the id DESC tie-break pinned; an off-list key is refused fail-closed.
     */
    private String orderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "文档");
        if (column == null) {
            return " ORDER BY id DESC";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", id DESC";
    }

    @Override
    public PageResult<DocumentInfo> list(long projectId, Integer status, String keyword, String category,
                                         int page, int size, String sortBy, String sortDir) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(200, Math.max(1, size == 0 ? 20 : size));
        StringBuilder where = new StringBuilder(" WHERE project_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status);
        }
        if (category != null && !category.isBlank()) {
            where.append(" AND doc_category = ?");
            args.add(category.trim().toUpperCase());
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND name ILIKE ?");
            args.add("%" + keyword.trim() + "%");
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_document" + where, Long.class, args.toArray());
        args.add(safeSize);
        args.add((safePage - 1) * safeSize);
        List<DocumentInfo> list = jdbcTemplate.query(
                DOCUMENT_COLUMNS + where + orderClause(sortBy, sortDir) + " LIMIT ? OFFSET ?",
                MAPPER, args.toArray());
        return new PageResult<>(list, total == null ? 0L : total);
    }

    @Override
    @Transactional
    @Audit(objectType = "document", action = "doc_delete", objectId = "#id")
    public void delete(long operatorId, long id) {
        DocumentInfo current = jdbcTemplate.query(DOCUMENT_COLUMNS + " WHERE id = ?", MAPPER, id)
                .stream().findFirst().orElse(null);
        if (current == null) {
            throw new ServiceException("文档不存在（或已删除）"
                    + "，请点「刷新」确认列表最新记录，无需重复删除", BizErrorCode.NOT_FOUND);
        }
        AuditLogContext.setBefore(AuditSnapshot.snapshot(current));
        String storageKey = current.storageKey();
        jdbcTemplate.update("UPDATE tp_requirement SET source_id=NULL, updated_at=now() "
                        + "WHERE source_id=?", ExtractionSourceId.forDocument(id));
        jdbcTemplate.update("DELETE FROM tp_document WHERE id = ?", id);
        // Ledger lifetime: the process-event faces of a deleted document have no
        // owner row left, so they are purged in the same transaction — an orphan
        // stream must never stay readable under a reused id. Same transaction is
        // deliberate: the document row and its event faces go together or stay
        // together, no half-deleted state where the drawer still tails a dead job.
        eventTrimSweeper.purgeJobEvents(AiTaskEventTrimSweeper.KIND_DOC_EXTRACT, String.valueOf(id));
        eventTrimSweeper.purgeJobEvents(AiTaskEventTrimSweeper.KIND_QA_GEN, String.valueOf(id));
        artifactStorage.deleteQuietly(storageKey);
    }

    private void assertProjectExists(long projectId) {
        Boolean exists = jdbcTemplate.query("SELECT 1 FROM tp_project WHERE id = ?",
                (ResultSetExtractor<Boolean>) rs -> rs.next(), projectId);
        if (exists == null || !exists) {
            throw new ServiceException("项目不存在（或已删除）"
                    + "，请先在顶栏切换项目，再进入「需求提取」页操作", BizErrorCode.NOT_FOUND);
        }
    }

    
    private static String resolveFileType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) {
            throw new ServiceException("不支持的文件类型，仅支持 pdf / markdown（.md/.markdown），"
                    + "请重新选择文件上传，正确写法示例：需求说明.md / spec.pdf",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String ext = lower.substring(dot + 1);
        if ("pdf".equals(ext)) {
            return TYPE_PDF;
        }
        if ("md".equals(ext) || "markdown".equals(ext)) {
            return TYPE_MARKDOWN;
        }
        throw new ServiceException("不支持的文件类型 ." + ext + "，仅支持 pdf / markdown（.md/.markdown），"
                + "请改名或转存为 .pdf / .md / .markdown 后重新上传，正确写法示例：spec.pdf",
                BizErrorCode.VALIDATION_ERROR);
    }

    private void assertSizeAllowed(long sizeBytes) {
        int maxMb = systemConfigReader.getInt(MAX_SIZE_MB_KEY, DEFAULT_MAX_SIZE_MB);
        if (sizeBytes > (long) maxMb * 1024 * 1024) {
            throw new ServiceException("文件超过大小上限 " + maxMb + "MB，"
                    + "请拆分文档分批上传，或在「系统设置」将 ai.doc.max-size-mb 调整为更大值后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest.digest(content)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Lineage group of a logical document = project + display name + file type
     * (single definition shared with the auto-pair query in the change-analysis
     * service, which reads the lineage_group_id column — no second naming rule).
     * Newest sibling wins the group id so a deleted root cannot fork the lineage.
     */
    private Long findLineageGroupId(long projectId, String displayName, String fileType) {
        List<Long> rows = jdbcTemplate.query(
                "SELECT lineage_group_id FROM tp_document"
                        + " WHERE project_id = ? AND name = ? AND file_type = ?"
                        + " AND lineage_group_id IS NOT NULL ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> rs.getLong(1), projectId, displayName, fileType);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
 * Group-scoped sha256 gate: identical content inside the same lineage group
 * = pointless duplicate 409; identical content under a different name is a
 */
    private void assertNotDuplicatedInGroup(String fileHash, String filename, Long lineageGroupId) {
        if (lineageGroupId == null) {
            return;
        }
        List<String> same = jdbcTemplate.query(
                "SELECT name FROM tp_document WHERE lineage_group_id = ? AND file_hash = ?"
                        + " ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> rs.getString(1), lineageGroupId, fileHash);
        if (!same.isEmpty()) {
            throw new ServiceException("文档已存在（内容与该文档已上传版本相同：" + filename + "），"
                    + "如需保留两份请改名后上传，或在「需求提取」列表「文档血缘」列查看历史版本",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    
    private static String stripExtension(String filename, String fileType) {
        String lower = filename.toLowerCase();
        String suffix = "." + ("markdown".equals(fileType)
                ? (lower.endsWith(".markdown") ? "markdown" : "md") : "pdf");
        return filename.substring(0, filename.length() - suffix.length());
    }

    
    private static String resolveCategory(String category) {
        if (category == null || category.isBlank()) {
            return CATEGORY_TEST_SPEC;
        }
        String upper = category.trim().toUpperCase();
        if (!CATEGORIES.contains(upper)) {
            throw new ServiceException("不支持的文档分类 " + category
                    + "，仅支持 TEST_SPEC（测试规格说明）/ REQUIREMENT_DOC（需求文档）"
                    + " / DDL_DICT（数据字典）/ REFERENCE（参考文档），"
                    + "请在「上传文档」对话框的「分类」下拉中选择，正确写法：TEST_SPEC / REQUIREMENT_DOC / DDL_DICT / REFERENCE",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return upper;
    }

    /**
     * Hot-update read of the extraction-participation category whitelist
     * (upper-case set). Missing row / blank value falls back to the shipped
     * default, which is the equivalence fold-in of the historical gate
     * (TEST_SPEC/REQUIREMENT_DOC/DDL_DICT on, REFERENCE off).
     */
    private java.util.Set<String> extractionAllowCategories() {
        String raw = systemConfigReader.getString(EXTRACTION_ALLOW_CATEGORIES_KEY,
                DEFAULT_EXTRACTION_ALLOW_CATEGORIES);
        java.util.Set<String> parsed = new java.util.HashSet<>();
        for (String piece : raw.split(",")) {
            String token = piece.trim().toUpperCase();
            if (!token.isEmpty()) {
                parsed.add(token);
            }
        }
        return parsed;
    }

    private Long insertPlaceholder(long projectId, String name, String fileType, String fileHash,
                                   long sizeBytes, long operatorId, String category,
                                   Long lineageGroupId) {
        try {
            // Usage switches start from category-derived defaults (single source:
            // this insert). The extraction participation default comes from the
            // hot-updatable category whitelist (V415 seed, equivalence fold-in of
            // the historical !REFERENCE gate -- REFERENCE documents stay out of
            // the extraction queue, every other category stays in, never
            // tightened). Prompt and grounding participation stay on for every
            // category. lineage_group_id may be null here: the upload path
            // self-roots the first member of a fresh lineage right after this
            // insert.
            boolean useForExtraction = extractionAllowCategories().contains(category);
            return jdbcTemplate.queryForObject("""
                    INSERT INTO tp_document (project_id, name, file_type, file_hash, size_bytes,
                                            status, created_by, doc_category,
                                            use_for_extraction, use_in_case_prompt, use_in_grounding,
                                            lineage_group_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, TRUE, ?) RETURNING id
                    """, Long.class, projectId, name, fileType, fileHash, sizeBytes,
                    STATUS_PARSING, operatorId, category, useForExtraction, lineageGroupId);
        } catch (DuplicateKeyException e) {
            throw new ServiceException("文档已存在（并发重复上传）"
                    + "，请点「刷新」确认已入库记录，无需再次上传", BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    
    private String extractText(String fileType, byte[] bytes) throws IOException {
        if (!TYPE_PDF.equals(fileType)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(bytes))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    private void insertChunks(long docId, List<ChunkPiece> pieces) {
        int seq = 1;
        for (ChunkPiece piece : pieces) {
            jdbcTemplate.update("INSERT INTO tp_document_chunk (document_id, chapter, seq, content,"
                    + " token_est) VALUES (?, ?, ?, ?, ?)",
                    docId, piece.chapter(), seq, piece.content(), piece.content().length());
            seq++;
        }
    }

    private DocumentInfo markSuccess(long docId, String storageKey, int chunkCount,
                                     long projectId, long operatorId, String category) {
        jdbcTemplate.update("UPDATE tp_document SET storage_key = ?, status = ?, chunk_count = ?,"
                + " error_msg = NULL, updated_at = now() WHERE id = ?",
                storageKey, STATUS_SUCCESS, chunkCount, docId);
        if (CATEGORY_DDL_DICT.equals(category)) {
            aiGenPublisher.publishKbIngest(new AiKbIngestMessage(
                    docId, projectId, operatorId, System.currentTimeMillis(), category));
        }
        return load(docId);
    }

    private DocumentInfo markFailed(long docId, String storageKey, String errorMsg) {
        String capped = errorMsg.length() > 480 ? errorMsg.substring(0, 480) : errorMsg;
        jdbcTemplate.update("UPDATE tp_document SET storage_key = ?, status = ?, error_msg = ?,"
                + " updated_at = now() WHERE id = ?", storageKey, STATUS_FAILED, capped, docId);
        return load(docId);
    }

    private DocumentInfo load(long docId) {
        return jdbcTemplate.queryForObject(DOCUMENT_COLUMNS + " WHERE id = ?", MAPPER, docId);
    }

    @Override
    @Transactional
    @Audit(objectType = "document", action = "doc_category_update", objectId = "#id",
            after = "#result")
    public DocumentInfo updateCategory(long operatorId, long id, String category,
                                       Integer revision) {
        // 404 human-readable first (same door as get), then required-field guards,
        // then the optimistic-lock write -- rejected writes never touch the row.
        get(id);
        requireRevision(revision);
        if (category == null || category.isBlank()) {
            throw new ServiceException("修改文档分类必须给出目标分类"
                    + "（TEST_SPEC/REQUIREMENT_DOC/DDL_DICT/REFERENCE），"
                    + "请在列表行的「分类」下拉中选择目标分类，正确写法：TEST_SPEC / REQUIREMENT_DOC / DDL_DICT / REFERENCE",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String target = resolveCategory(category);
        int updated = jdbcTemplate.update(
                "UPDATE tp_document SET doc_category = ?, revision = revision + 1,"
                        + " updated_at = now() WHERE id = ? AND revision = ?",
                target, id, revision);
        if (updated == 0) {
            throw revisionConflict(id);
        }
        return load(id);
    }

    @Override
    @Transactional
    @Audit(objectType = "document", action = "doc_usage_update", objectId = "#id",
            after = "#result")
    public DocumentInfo updateUsageSwitches(long operatorId, long id, Boolean useForExtraction,
                                            Boolean useInCasePrompt, Boolean useInGrounding,
                                            Integer revision) {
        get(id);
        requireRevision(revision);
        List<String> missing = new ArrayList<>();
        if (useForExtraction == null) {
            missing.add("use_for_extraction");
        }
        if (useInCasePrompt == null) {
            missing.add("use_in_case_prompt");
        }
        if (useInGrounding == null) {
            missing.add("use_in_grounding");
        }
        if (!missing.isEmpty()) {
            throw new ServiceException("文档用途开关必须逐项给出目标值（缺少："
                    + String.join("、", missing) + "），"
                    + "请在列表行「参与用途」中逐项开启「需求提取」「用例生成」「知识接地」后保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int updated = jdbcTemplate.update(
                "UPDATE tp_document SET use_for_extraction = ?, use_in_case_prompt = ?,"
                        + " use_in_grounding = ?, revision = revision + 1, updated_at = now()"
                        + " WHERE id = ? AND revision = ?",
                useForExtraction, useInCasePrompt, useInGrounding, id, revision);
        if (updated == 0) {
            throw revisionConflict(id);
        }
        return load(id);
    }

    /** Lock token is mandatory (same posture as the V90 plugin/tool edit doors). */
    private static void requireRevision(Integer revision) {
        if (revision == null) {
            throw new ServiceException("编辑文档必须携带 revision（并发保护版本号，"
                    + "请点「刷新」回到「需求提取」列表重新打开编辑后重试）", BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** Optimistic-lock conflict, human-readable and naming the current revision. */
    private ServiceException revisionConflict(long id) {
        return new ServiceException("文档已被他人修改，请点「刷新」获取最新版本后重试（当前版本 "
                + get(id).revision() + "）", BizErrorCode.VERSION_CONFLICT);
    }

    
    @Override
    public List<Map<String, Object>> chunks(long id) {
        get(id);
        return jdbcTemplate.query("SELECT id, chapter, seq, content FROM tp_document_chunk"
                        + " WHERE document_id = ? ORDER BY seq",
                (rs, rowNum) -> {
                    Map<String, Object> chunk = new LinkedHashMap<>();
                    // AP-4 : row id exposed so the extraction chain can anchor an
                    chunk.put("id", rs.getLong("id"));
                    chunk.put("chapter", rs.getString("chapter"));
                    chunk.put("seq", rs.getInt("seq"));
                    chunk.put("content", rs.getString("content"));
                    return chunk;
                }, id);
    }

    /**
 * segment B context door. Nearest-first accumulation under the
 * hot-updatable budget keeps the decision stable: the closest neighbours
 * survive, the farthest ones drop first, and the requested chunk's own
 * text is never trimmed (it is the anchor the user clicked).
 */
    @Override
    public com.sixbean.testpike.core.document.api.dto.ChunkContext chunkContext(
            long documentId, long chunkId) {
        get(documentId);
        List<java.util.Map<String, Object>> rows = chunks(documentId);
        int index = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (((Number) rows.get(i).get("id")).longValue() == chunkId) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            // A stale or foreign chunk id is an expected answer state, not an
            // error: the record still carries the excerpt taken at adoption
            // time, so the door opens on the fallback face instead of putting
            // a 4xx on the wire (E2E zero tolerance + honest fallback).
            // No text is returned here, so a cross-document probe learns
            // nothing beyond "not in this document's chunk set".
            return new com.sixbean.testpike.core.document.api.dto.ChunkContext(
                    chunkId, 0, null, null, 0, 0, List.of(), false);
        }
        java.util.Map<String, Object> self = rows.get(index);
        int budget = resolveContextBudgetChars();
        List<com.sixbean.testpike.core.document.api.dto.ChunkNeighbor> kept =
                new java.util.ArrayList<>();
        long usedChars = 0;
        for (int i = index - 1; i >= 0 && kept.size() < 3; i--) {
            java.util.Map<String, Object> row = rows.get(i);
            String content = (String) row.get("content");
            long chars = content == null ? 0 : content.length();
            if (usedChars + chars > budget) {
                break;
            }
            usedChars += chars;
            kept.add(0, new com.sixbean.testpike.core.document.api.dto.ChunkNeighbor(
                    ((Number) row.get("id")).longValue(),
                    (String) row.get("chapter"),
                    ((Number) row.get("seq")).intValue(),
                    content));
        }
        String selfContent = (String) self.get("content");
        return new com.sixbean.testpike.core.document.api.dto.ChunkContext(
                chunkId,
                ((Number) self.get("seq")).intValue(),
                (String) self.get("chapter"),
                selfContent,
                selfContent == null ? 0 : selfContent.length(),
                index + 1,
                kept,
                true);
    }

    /** Budget read with fail-closed fallback to the shipped default. */
    private int resolveContextBudgetChars() {
        String raw = configService.getValue(CHUNK_CONTEXT_CHARS_KEY,
                String.valueOf(DEFAULT_CHUNK_CONTEXT_CHARS));
        try {
            int value = Integer.parseInt(raw.trim());
            return value < 1 ? DEFAULT_CHUNK_CONTEXT_CHARS : value;
        } catch (RuntimeException e) {
            return DEFAULT_CHUNK_CONTEXT_CHARS;
        }
    }
}
