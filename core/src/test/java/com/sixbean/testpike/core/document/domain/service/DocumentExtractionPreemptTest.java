package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.idempotent.ExtractDedupKeys;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * core entry preemption contract (17 TC-XQ-05 / 18 IT-XQ-01 family).
 * <p>While the previous extraction generation is still live (its consume-lock
 * heartbeat warm in Redis), the extract entry no longer answers a blind
 * "please wait": without the preempt flag it names the preemption consequence
 * in the rejection, with preempt=true it admits the new generation and writes
 * a fresh token (the old ai-service run then self-aborts at its generation
 * seams, and its writebacks are 409-guarded anyway).
 * <p>Real dev DB + real Redis (strict testing: no mocks). The heartbeat seed is
 * assembled via {@link ExtractDedupKeys} — never a re-spelled literal — so the
 * test breaks together with any drift of the single-source key grammar.
 * Rows/keys created here are deleted by id in @AfterEach (R37: no TRUNCATE of
 * shipped tables, no pollution of the demo projects).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {
        "testpike.scheduling.enabled=false"})
class DocumentExtractionPreemptTest {

    @Autowired
    private DocumentExtractionTestRig rig;

    private final List<Long> createdDocIds = new ArrayList<>();
    private final List<String> seededKeys = new ArrayList<>();
    private Long createdProjectId;

    private long createProject() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        createdProjectId = rig.projectService.create(1L,
                new ProjectCreateInput("抢占测试项目" + code, code, "zh-CN", "P1", null));
        return createdProjectId;
    }

    /** Upload a parseable MD doc (content unique per case = sha256 dedup safe). */
    private long uploadDoc(long projectId) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String md = "# 抢占回归 PRD（" + tag + "）\n\n## 会话保持\n\n"
                + "登录态过期前应提示续期。\n";
        DocumentInfo info = rig.documentService.upload(1L, projectId,
                "PREEMPT-" + tag + ".md", md.getBytes(StandardCharsets.UTF_8), null);
        assertEquals(1, info.status(), "前置文档必须解析成功");
        createdDocIds.add(info.id());
        return info.id();
    }

    /** Seed a live old-generation heartbeat (TTL 60s, shape from the single source). */
    private String seedLiveHeartbeat(long docId) {
        String oldToken = UUID.randomUUID().toString();
        String key = ExtractDedupKeys.consumeHeartbeatKey(docId, oldToken);
        rig.redis.opsForValue().set(key, "seed-holder-" + oldToken, 60,
                java.util.concurrent.TimeUnit.SECONDS);
        seededKeys.add(key);
        return oldToken;
    }

    private void forceStatus3(long docId, String token) {
        rig.jdbcTemplate.update("UPDATE tp_document SET status=3, extract_token=? WHERE id=?",
                token, docId);
    }

    private String dbToken(long docId) {
        return rig.jdbcTemplate.query("SELECT extract_token FROM tp_document WHERE id=?",
                rs -> rs.next() ? rs.getString(1) : null, docId);
    }

    @AfterEach
    void cleanup() {
        seededKeys.forEach(rig.redis::delete);
        ExtractionTestCleanup.deleteDocuments(rig.jdbcTemplate, createdDocIds);
        ExtractionTestCleanup.deleteProject(rig.jdbcTemplate, createdProjectId);
        createdDocIds.clear();
        seededKeys.clear();
        createdProjectId = null;
    }

    /** Live old-generation heartbeat + no preempt flag = the rejection names the
     *  preemption consequence (never the generic "please wait"); token untouched. */
    @Test
    void liveGenerationWithoutPreemptFlagNamesPreemptionInRejection() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        seedLiveHeartbeat(docId);
        forceStatus3(docId, "seed-old-token");

        ServiceException e = assertThrows(ServiceException.class,
                () -> rig.extractionService.extract(1L, docId, null));
        assertTrue(e.getMessage().contains("抢占"),
                "锁活无参应点名抢占后果，实际=" + e.getMessage());
        assertEquals("seed-old-token", dbToken(docId), "拒止路径 token 零漂移");
    }

    /** Live heartbeat + preempt=true = admitted: status 3 with a FRESH generation
     *  token (the superseded run's writebacks stay 409-guarded). */
    @Test
    void preemptFlagAdmitsNewGenerationWithFreshToken() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        String oldToken = seedLiveHeartbeat(docId);
        forceStatus3(docId, oldToken);

        DocumentInfo info = rig.extractionService.extract(1L, docId, null, null, true);
        assertEquals(3, info.status(), "抢占放行后进入提取中态");
        String fresh = dbToken(docId);
        assertNotEquals(oldToken, fresh, "必须写入新轮次 token");
        assertTrue(fresh != null && !fresh.isBlank(), "新 token 非空");
    }

    /** supersedes the legacy wait rejection: status=3 with NO live
 * heartbeat is dead residue — the plain extract click is admitted on the
 * spot with a fresh generation token (no sweeper wait, no preempt flag). */
    @Test
    void staleExtractingWithoutLockIsAdmittedOnTheSpot() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        forceStatus3(docId, "stale-token-no-heartbeat");

        DocumentInfo info = rig.extractionService.extract(1L, docId, null);
        assertEquals(3, info.status(), "锁无主应直接放行进入提取中");
        assertTrue(!"stale-token-no-heartbeat".equals(dbToken(docId)),
                "放行必须写入新轮次 token");
    }

    /** Plain status-1 dispatch is unaffected by the probe (behaviour regression guard). */
    @Test
    void plainExtractUnaffectedByProbe() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);

        DocumentInfo info = rig.extractionService.extract(1L, docId, null);
        assertEquals(3, info.status(), "正常提取派发进入 3 态");
    }
}
