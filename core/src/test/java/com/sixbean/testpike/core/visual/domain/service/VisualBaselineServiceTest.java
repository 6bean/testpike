package com.sixbean.testpike.core.visual.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.visual.api.dto.VisualBaselineCandidateInput;
import com.sixbean.testpike.core.visual.api.dto.VisualBaselineInfo;
import com.sixbean.testpike.core.visual.domain.component.VisualBaselineStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class VisualBaselineServiceTest {

    @Autowired
    private VisualBaselineService visualBaselineService;

    @Autowired
    private ArtifactStorage artifactStorage;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * tp_visual_baseline carries a NOT NULL project_id with no FK into
     * tp_project (live schema scan 2026-11-16), so the generic FK walk cannot
     * reach it: it joins the project sweep as an explicit extra table.
     */
    private static final List<String> VISUAL_EXTRA_TABLES = List.of("tp_visual_baseline");

    /** Name prefix of every fixture project this suite creates. */
    private static final String VISUAL_NAME_PREFIX = "视觉基线测试";

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // R37 fixture-scoped teardown: every fixture project of this suite is
        // named with the VISUAL_NAME_PREFIX below plus a per-run code, so the
        // crashed-run family resolves by that name prefix and the shared
        // project sweep purges the baselines, the membership rows and the
        // audit trail of exactly those projects. The blanket table wipe this
        // replaced erased baselines of shipped and concurrent-suite projects
        // on the shared dev database.
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, VISUAL_EXTRA_TABLES,
                SharedDbFixtureSweep.projectIdsByNamePrefixes(jdbcTemplate,
                        List.of(VISUAL_NAME_PREFIX)));
    }

    private long createProject(Integer storageMode) {
        String code = "VB" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, default_lang, default_priority, storage_mode)"
                        + " VALUES ('视觉基线测试' || ?, ?, 'zh-CN', 'P1', COALESCE(?::int, 1)) RETURNING id",
                Long.class, code, code, storageMode);
        assertNotNull(id);
        return id;
    }

    
    private String seedSourceScreenshot(String name, byte[] bytes) {
        String key = "testpike/9001/exec-artifact/202609/ab12cd34-screenshot_" + name + ".png";
        artifactStorage.putBytes(key, bytes, "image/png");
        return key;
    }

    private VisualBaselineCandidateInput candidate(long projectId, String stepKey, String sourceKey) {
        return new VisualBaselineCandidateInput(projectId, stepKey, 9001L, sourceKey, 0.02);
    }

    @Test
    void 候选登记落pending_未确认期同stepKey覆盖来源不新建行() {
        long pid = createProject(null);
        String keyA = seedSourceScreenshot("a", pngBytes(10, 0x11));
        VisualBaselineInfo first = visualBaselineService.recordCandidate(
                candidate(pid, "case:100:step:3:home", keyA));
        assertEquals(VisualBaselineStatus.PENDING, first.status());
        assertEquals(0.02, first.diffTolerance());
        assertNotNull(first.stepKey());
        assertNull(first.baselineKey(), "pending 阶段基线键未落");

        String keyB = seedSourceScreenshot("b", pngBytes(10, 0x12));
        VisualBaselineInfo second = visualBaselineService.recordCandidate(
                candidate(pid, "case:100:step:3:home", keyB));
        assertEquals(first.id(), second.id(), "未确认期再采集=覆盖来源不新建行");
        assertEquals(keyB, second.sourceArtifactKey(), "来源截图键推进到最新采集");
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_visual_baseline WHERE project_id = ?",
                Long.class, pid));
    }

    @Test
    void 云态项目_确认升格读回字节一致_旧基线取代版本链完整() {
        long pid = createProject(3);
        byte[] png1 = pngBytes(10, 0x21);
        byte[] png2 = pngBytes(10, 0x22);

        VisualBaselineInfo c1 = visualBaselineService.recordCandidate(
                candidate(pid, "case:1:step:1:p", seedSourceScreenshot("v1", png1)));
        VisualBaselineInfo confirmed1 = visualBaselineService.confirm(c1.id(), 1L);
        assertEquals(VisualBaselineStatus.CONFIRMED, confirmed1.status());
        assertNotNull(confirmed1.baselineKey(), "确认后基线稳定键落位");
        assertTrue(confirmed1.baselineKey().contains("/" + pid + "/visual-baseline/"),
                "基线键=项目路由+visual-baseline 场景规范: " + confirmed1.baselineKey());
        assertArrayEquals(png1, visualBaselineService.readBaselineBytes(confirmed1.baselineKey()));

        VisualBaselineInfo c2 = visualBaselineService.recordCandidate(
                candidate(pid, "case:1:step:1:p", seedSourceScreenshot("v2", png2)));
        assertNotEquals(c1.id(), c2.id(), "已确认后重采集=新 pending 行（版本链）");
        VisualBaselineInfo confirmed2 = visualBaselineService.confirm(c2.id(), 1L);
        assertEquals(VisualBaselineStatus.SUPERSEDED,
                visualBaselineService.getById(confirmed1.id()).status(), "旧行被取代（重读盘上状态）");
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_visual_baseline WHERE status = 2 AND project_id = ?"
                        + " AND step_key = 'case:1:step:1:p'",
                Long.class, pid),
                "同 stepKey 至多一行 confirmed");
        assertArrayEquals(png2, visualBaselineService.readBaselineBytes(confirmed2.baselineKey()));

        List<VisualBaselineInfo> chain = visualBaselineService.listByStep(pid, "case:1:step:1:p");
        assertEquals(2, chain.size(), "版本链=当前+被取代共 2 行");
        assertEquals(VisualBaselineStatus.CONFIRMED, chain.get(0).status(), "id DESC 当前基线置顶");
        assertEquals("当前基线", chain.get(0).statusLabel());
        assertEquals("已被取代", chain.get(1).statusLabel());
    }

    @Test
    void 本地态项目_基线随项目路由落盘读回一致() {
        long pid = createProject(2);
        byte[] png = pngBytes(8, 0x33);
        VisualBaselineInfo c = visualBaselineService.recordCandidate(
                candidate(pid, "case:7:step:2:page", seedSourceScreenshot("l", png)));
        VisualBaselineInfo confirmed = visualBaselineService.confirm(c.id(), 1L);
        assertArrayEquals(png, visualBaselineService.readBaselineBytes(confirmed.baselineKey()));
        assertNotNull(visualBaselineService.grantBaselineViewUrl(confirmed.baselineKey()));
    }

    @Test
    void 非pending确认拒绝_来源字节缺失fail_closed_人话点名() {
        long pid = createProject(null);
        VisualBaselineInfo c = visualBaselineService.recordCandidate(
                candidate(pid, "case:2:step:1:x", seedSourceScreenshot("n", pngBytes(4, 0x44))));
        visualBaselineService.confirm(c.id(), 1L);
        ServiceException twice = assertThrows(ServiceException.class,
                () -> visualBaselineService.confirm(c.id(), 1L));
        assertEquals(BizErrorCode.VALIDATION_ERROR, twice.getCode());
        assertTrue(twice.getMessage().contains("仅待确认"), "人话点名状态: " + twice.getMessage());

        VisualBaselineInfo ghost = visualBaselineService.recordCandidate(
                new VisualBaselineCandidateInput(pid, "case:3:step:1:y", 1L,
                        "testpike/9001/exec-artifact/202609/deadbe0f-screenshot_gone.png", null));
        ServiceException noBytes = assertThrows(ServiceException.class,
                () -> visualBaselineService.confirm(ghost.id(), 1L));
        assertTrue(noBytes.getMessage().contains("截图"), "缺字节人话拒: " + noBytes.getMessage());
    }

    @Test
    void 值域闸_容差越界_stepKey空白_来源键空_项目缺失_点名拒() {
        long pid = createProject(null);
        ServiceException tol = assertThrows(ServiceException.class, () -> visualBaselineService.recordCandidate(
                new VisualBaselineCandidateInput(pid, "case:1:step:1:a", 1L, "k", 1.5)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, tol.getCode());
        assertTrue(tol.getMessage().contains("容差"), tol.getMessage());

        assertThrows(ServiceException.class, () -> visualBaselineService.recordCandidate(
                new VisualBaselineCandidateInput(pid, "  ", 1L, "k", null)));

        assertThrows(ServiceException.class, () -> visualBaselineService.recordCandidate(
                new VisualBaselineCandidateInput(pid, "case:1:step:1:a", 1L, "", null)));

        assertThrows(ServiceException.class, () -> visualBaselineService.recordCandidate(
                new VisualBaselineCandidateInput(999999L, "case:1:step:1:a", 1L, "k", null)));
    }

    @Test
    void 拒绝候选_删pending不留孤儿_已确认不可拒() {
        long pid = createProject(null);
        VisualBaselineInfo c = visualBaselineService.recordCandidate(
                candidate(pid, "case:9:step:1:reject", seedSourceScreenshot("r", pngBytes(2, 0x51))));
        visualBaselineService.reject(c.id(), 1L);
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_visual_baseline WHERE id=" + c.id(), Long.class));

        VisualBaselineInfo ok = visualBaselineService.recordCandidate(
                candidate(pid, "case:9:step:1:keep", seedSourceScreenshot("k", pngBytes(2, 0x55))));
        visualBaselineService.confirm(ok.id(), 1L);
        assertThrows(ServiceException.class, () -> visualBaselineService.reject(ok.id(), 1L));
    }

    @Test
    void claim读路径_无基线回空_有基线只回元数据带容差() {
        long pid = createProject(null);
        VisualBaselineInfo pending = visualBaselineService.recordCandidate(
                candidate(pid, "case:11:step:1:c", seedSourceScreenshot("p", pngBytes(3, 0x66))));
        assertNull(visualBaselineService.findConfirmed(pid, "case:11:step:1:c"),
                "无已确认基线时 claim 读路径回空=执行器采集首图语义");

        VisualBaselineInfo confirmed = visualBaselineService.confirm(pending.id(), 1L);
        VisualBaselineInfo read = visualBaselineService.findConfirmed(pid, "case:11:step:1:c");
        assertNotNull(read);
        assertEquals(confirmed.baselineKey(), read.baselineKey());
        assertEquals(0.02, read.diffTolerance(), "容差随 claim 下发");
        assertNotNull(visualBaselineService.grantBaselineViewUrl(read.baselineKey()), "查看授权 URL 可签发");
    }

    @Test
    void 按用例读全版本链_隔离他用例键_新行在前() {
        long pid = createProject(null);
        visualBaselineService.recordCandidate(
                candidate(pid, "case:21:step:1:lc", seedSourceScreenshot("l1", pngBytes(2, 0x71))));
        VisualBaselineInfo k2 = visualBaselineService.recordCandidate(
                candidate(pid, "case:21:step:2:lc2", seedSourceScreenshot("l2", pngBytes(2, 0x72))));
        visualBaselineService.confirm(k2.id(), 1L);
        visualBaselineService.recordCandidate(
                candidate(pid, "case:22:step:1:lc", seedSourceScreenshot("o1", pngBytes(2, 0x73))));
        long otherPid = createProject(null);
        visualBaselineService.recordCandidate(
                candidate(otherPid, "case:21:step:3:lc", seedSourceScreenshot("o2", pngBytes(2, 0x74))));

        List<VisualBaselineInfo> chain = visualBaselineService.listByCase(pid, 21L);
        assertEquals(2, chain.size(), "只回本项目本用例的基线行");
        assertEquals("case:21:step:2:lc2", chain.get(0).stepKey(), "id DESC 新行在前");
        assertEquals(VisualBaselineStatus.CONFIRMED, chain.get(0).status());
        assertEquals(VisualBaselineStatus.PENDING, chain.get(1).status());
    }

    @Test
    void 行查看授权_pending回来源图_confirmed回基线图() {
        long pid = createProject(null);
        VisualBaselineInfo pending = visualBaselineService.recordCandidate(
                candidate(pid, "case:23:step:1:vu", seedSourceScreenshot("v", pngBytes(3, 0x77))));
        String pendingUrl = visualBaselineService.viewUrl(pending.id());
        assertNotNull(pendingUrl);
        assertTrue(pendingUrl.contains("http"), "预签名查看 URL 可签发（pending=来源截图）");

        VisualBaselineInfo confirmed = visualBaselineService.confirm(pending.id(), 1L);
        assertNotNull(visualBaselineService.viewUrl(confirmed.id()), "confirmed=基线对象键");
        jdbcTemplate.update("UPDATE tp_visual_baseline SET source_artifact_key = NULL WHERE id = ?",
                visualBaselineService.recordCandidate(candidate(pid, "case:23:step:9:vu",
                        seedSourceScreenshot("z", pngBytes(1, 0x78)))).id());
        VisualBaselineInfo blankKey = visualBaselineService.listByStep(pid, "case:23:step:9:vu").get(0);
        assertThrows(ServiceException.class, () -> visualBaselineService.viewUrl(blankKey.id()),
                "来源键缺失=人话拒绝，不签空键 URL");
        assertEquals(confirmed.status(), VisualBaselineStatus.CONFIRMED);
    }

    @Test
    void 唯一索引结构性兜底_双confirmed同stepKey撞库() {
        long pid = createProject(null);
        visualBaselineService.confirm(
                visualBaselineService.recordCandidate(candidate(pid, "case:13:step:1:race",
                        seedSourceScreenshot("a", pngBytes(2, 0x11)))).id(), 1L);
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "INSERT INTO tp_visual_baseline (project_id, step_key, status, baseline_key)"
                        + " VALUES (?, 'case:13:step:1:race', 2,"
                        + " 'testpike/1/visual-baseline/202609/x-race.png')", pid));
    }

    @Test
    void 不存在候选_确认与读取_抛错人话() {
        assertThrows(ServiceException.class, () -> visualBaselineService.getById(999999L));
        assertThrows(ServiceException.class, () -> visualBaselineService.confirm(999999L, 1L));
        assertThrows(ServiceException.class, () -> visualBaselineService.reject(999999L, 1L));
    }

    private static byte[] pngBytes(int size, int seed) {
        byte[] data = new byte[size];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (seed + i);
        }
        return data;
    }
}
