package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * self-recovery contracts: fast admission of dead extraction residue
 * (17 TC-XQ-11 / 18 IT-XQ-06 family) plus the explicit cancel endpoint.
 * <p>Before this batch a status=3 row whose consumer died had to wait for the
 * stall sweeper before any retry was admitted — the user watched "extracting"
 * with no way out. Now the entry probe proves the residue is dead (no live
 * consume heartbeat) and admits the click on the spot. Cancel is the explicit
 * sibling: rotate the generation (the in-flight run self-aborts at its seams,
 * stale writebacks stay 409-guarded) and settle the row back to 1 with a
 * human-readable cancel reason.
 * <p>Real dev DB, no mocks (strict testing). Rows created here are deleted by
 * id in @AfterEach (R37: shipped tables untouched, demo projects unpolluted).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {
        "testpike.scheduling.enabled=false"})
class DocumentExtractionCancelTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DocumentExtractionService extractionService;
    @Autowired
    private DocumentService documentService;
    @Autowired
    private ProjectService projectService;

    private final List<Long> createdDocIds = new ArrayList<>();
    private Long createdProjectId;

    private long createProject() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        createdProjectId = projectService.create(1L,
                new ProjectCreateInput("取消恢复测试项目" + code, code, "zh-CN", "P1", null));
        return createdProjectId;
    }

    /** Upload a parseable MD doc (content unique per case = sha256 dedup safe). */
    private long uploadDoc(long projectId) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String md = "# 自助恢复回归 PRD（" + tag + "）\n\n## 会话保持\n\n"
                + "登录态过期前应提示续期。\n";
        DocumentInfo info = documentService.upload(1L, projectId,
                "CANCEL-" + tag + ".md", md.getBytes(StandardCharsets.UTF_8), null);
        assertEquals(1, info.status(), "前置文档必须解析成功");
        createdDocIds.add(info.id());
        return info.id();
    }

    /** Forge the dead-residue shape: status=3 with a token, no consume heartbeat. */
    private String forceDeadResidue(long docId) {
        String staleToken = "dead-residue-" + UUID.randomUUID();
        jdbcTemplate.update("UPDATE tp_document SET status=3, extract_token=?, "
                + "extract_error=? WHERE id=?",
                staleToken, "提取任务疑似中断（长时间未回写），已恢复为可提取状态，请重试提取", docId);
        return staleToken;
    }

    private String dbToken(long docId) {
        return jdbcTemplate.query("SELECT extract_token FROM tp_document WHERE id=?",
                rs -> rs.next() ? rs.getString(1) : null, docId);
    }

    private Integer dbStatus(long docId) {
        return jdbcTemplate.query("SELECT status FROM tp_document WHERE id=?",
                rs -> rs.next() ? rs.getInt(1) : null, docId);
    }

    private String dbError(long docId) {
        return jdbcTemplate.query("SELECT extract_error FROM tp_document WHERE id=?",
                rs -> rs.next() ? rs.getString(1) : null, docId);
    }

    @AfterEach
    void cleanup() {
        ExtractionTestCleanup.deleteDocuments(jdbcTemplate, createdDocIds);
        ExtractionTestCleanup.deleteProject(jdbcTemplate, createdProjectId);
        createdDocIds.clear();
        createdProjectId = null;
    }

    /** ①: status=3 with NO live heartbeat = dead residue — a plain
 * extract click is admitted on the spot (no preempt flag, no waiting for
 * the sweeper) and enters 3 with a FRESH generation token. */
    @Test
    void deadResidueExtractIsAdmittedOnTheSpot() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        String staleToken = forceDeadResidue(docId);

        DocumentInfo info = extractionService.extract(1L, docId, null);
        assertEquals(3, info.status(), "锁无主的死 residue 点击应当场放行进入提取中");
        String fresh = dbToken(docId);
        assertNotEquals(staleToken, fresh, "放行必须写入新轮次 token");
        assertNotNull(fresh);
    }

    /** ③: explicit cancel of a running extraction settles the row to 1
 * with a human-readable reason and rotates the generation so a late
 * writeback from the cancelled run is rejected by the guard. */
    @Test
    void cancelSettlesRowAndRotatesGenerationAgainstLateWriteback() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        String token = dbToken(docId);
        assertNotNull(token, "派发后应持有本轮 token");

        DocumentInfo cancelled = extractionService.cancelExtraction(1L, docId);
        assertEquals(1, cancelled.status(), "取消后回到可提取态");
        assertEquals(1, dbStatus(docId), "落库状态=1");
        String err = dbError(docId);
        assertNotNull(err, "取消必须回写人话原因");
        assertTrue(err.contains("取消"), "回写文案应点名取消动作，实际=" + err);
        assertNotEquals(token, dbToken(docId), "取消必须轮换轮次 token");
    }

    /** The cancelled generation may never write back: the stale-token result
     *  push hits the generation guard (409-style rejection), the row stays 1. */
    @Test
    void cancelledGenerationWritebackIsRejectedByGuard() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        String oldToken = dbToken(docId);

        extractionService.cancelExtraction(1L, docId);

        ServiceException e = assertThrows(ServiceException.class,
                () -> extractionService.writeExtractionResult(docId, List.of(), oldToken));
        // Rejection wording of record = DocumentExtractionServiceImpl.rejectionMessage
        assertTrue(e.getMessage().contains("已忽略"),
                "旧轮次回写应被守卫拒绝，实际=" + e.getMessage());
        assertEquals(1, dbStatus(docId), "被拒回写零改状态");
    }

    /** R28-style honest no-op: cancelling a document that is not extracting
     *  rejects with a human message and writes nothing. */
    @Test
    void cancelWithoutRunningExtractionRejectsHonestly() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        String beforeToken = dbToken(docId);

        ServiceException e = assertThrows(ServiceException.class,
                () -> extractionService.cancelExtraction(1L, docId));
        assertTrue(e.getMessage().contains("取消"), "应点名取消语义，实际=" + e.getMessage());
        assertEquals(dbToken(docId), beforeToken, "非提取态取消零写入");
        assertEquals(1, dbStatus(docId));
    }

    /** Double cancel = the second one meets the same honest rejection (the
     *  first already settled the row to 1). */
    @Test
    void secondCancelAfterSettlingRejectsAsNoRunningTask() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        extractionService.cancelExtraction(1L, docId);

        ServiceException e = assertThrows(ServiceException.class,
                () -> extractionService.cancelExtraction(1L, docId));
        assertTrue(e.getMessage().contains("取消"), "实际=" + e.getMessage());
    }
}
