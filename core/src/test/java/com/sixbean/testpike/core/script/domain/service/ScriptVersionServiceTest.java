package com.sixbean.testpike.core.script.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.api.dto.CaseUpdateInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.api.dto.ScriptVersionDiff;
import com.sixbean.testpike.core.script.api.dto.ScriptVersionInfo;
import com.sixbean.testpike.core.script.domain.component.ScriptVersionSupport;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ScriptVersionServiceTest {

    @Autowired
    private ScriptService scriptService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> versionLineTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        ProjectFixtureRig.sweep(jdbcTemplate, versionLineTrackedProjects);
    }

    private long createVersionLineProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                versionLineTrackedProjects, "版本线项目" + code, code);
    }

    private CaseInfo createCase(long projectId) {
        return caseService.create(1L, new CaseCreateInput(projectId, "登录功能测试", "打开登录页",
                "登录成功", 1, 2, "web", 1, null, 1, null,
                List.of(new CaseStepInput(1, "输入账号密码", 0))));
    }

    
    private static String actionJson(String title, String steps, String env) {
        return "{\"version\":\"v2\",\"caseId\":\"TC-SPV\",\"title\":\"" + title + "\",\"endType\":\"web\","
                + "\"setup\":[{\"action\":\"goto\",\"target\":{\"url\":\"/login\"}}],"
                + "\"steps\":[" + steps + "],\"env\":" + env + "}";
    }

    private static final String STEP_A = "{\"action\":\"input\",\"target\":{\"css\":\"#u\"},\"value\":\"a\"}";
    private static final String STEP_B = "{\"action\":\"expect\",\"target\":{\"text\":\"ok\"}}";

    private int snapshotCount(long caseId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_script_version WHERE case_id = ?", Integer.class, caseId);
        return n == null ? 0 : n;
    }

    @Test
    void save_写版本线快照_行字段与脚本序对齐() {
        long pid = createVersionLineProject("spv1");
        CaseInfo info = createCase(pid);

        scriptService.save(new ScriptSaveInput(info.id(), "v2", actionJson("登录A", STEP_A, "{\"ENV\":\"dev\"}"), 1));

        List<ScriptVersionInfo> versions = scriptService.listVersions(info.id());
        assertEquals(1, versions.size(), "首次保存即写快照（save 双分支均挂点）");
        ScriptVersionInfo v = versions.get(0);
        assertEquals(info.id(), v.caseId());
        assertEquals(1, v.caseVersion(), "对齐用例创建版本 v1");
        assertEquals(1, v.scriptVersion(), "脚本自身序 tp_script.version=1");
        assertEquals("v2", v.schemaVersion());
        assertEquals(ScriptVersionSupport.REASON_SAVE, v.reason());
        assertFalse(v.corrupted());
        Long scriptId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_script WHERE case_id = ?", Long.class, info.id());
        assertEquals(scriptId, v.scriptId());
        String stored = jdbcTemplate.queryForObject(
                "SELECT actions_json #>> '{title}' FROM tp_script_version WHERE case_id = ?",
                String.class, info.id());
        assertEquals("登录A", stored, "快照存动作 JSON 原文");
    }

    @Test
    void 二次保存_脚本序递增_用例版本不变() {
        long pid = createVersionLineProject("spv2");
        CaseInfo info = createCase(pid);
        scriptService.save(new ScriptSaveInput(info.id(), "v2", actionJson("登录A", STEP_A, "{\"ENV\":\"dev\"}"), 1));
        scriptService.save(new ScriptSaveInput(info.id(), "v2", actionJson("登录B", STEP_A + "," + STEP_B, "{\"ENV\":\"dev\"}"), 3));

        List<ScriptVersionInfo> versions = scriptService.listVersions(info.id());
        assertEquals(2, versions.size());
        assertEquals(2, versions.get(0).scriptVersion(), "第二次保存 script version=2");
        assertEquals(1, versions.get(0).caseVersion(), "脚本保存不改用例版本");
        assertEquals(1, versions.get(1).scriptVersion());
    }

    @Test
    void 用例编辑后保存_快照case_version对齐() {
        long pid = createVersionLineProject("spv3");
        CaseInfo info = createCase(pid);
        caseService.update(1L, info.id(), new CaseUpdateInput("登录功能测试改", null, null,
                null, null, null, null, null, null, null), info.version());
        scriptService.save(new ScriptSaveInput(info.id(), "v2", actionJson("登录A", STEP_A, "{\"ENV\":\"dev\"}"), 1));

        List<ScriptVersionInfo> versions = scriptService.listVersions(info.id());
        assertEquals(1, versions.size());
        assertEquals(2, versions.get(0).caseVersion(), "快照对齐编辑后的用例版本 v2（坑 b 语义）");
    }

    @Test
    void diff_字段级变更_基准缺省取前一快照() {
        long pid = createVersionLineProject("spv4");
        CaseInfo info = createCase(pid);
        scriptService.save(new ScriptSaveInput(info.id(), "v2", actionJson("登录A", STEP_A, "{\"ENV\":\"dev\"}"), 1));
        scriptService.save(new ScriptSaveInput(info.id(), "v2", actionJson("登录B", STEP_A + "," + STEP_B, "{\"ENV\":\"test\"}"), 1));

        ScriptVersionDiff diff = scriptService.diffVersion(info.id(), 2, null);
        assertEquals(1, diff.fromVersion());
        assertEquals(2, diff.toVersion());
        assertFalse(diff.corrupted());
        List<String> fields = diff.changes().stream()
                .map(c -> c.field()).filter(f -> List.of("title", "steps", "env").contains(f)).sorted().toList();
        assertEquals(List.of("env", "steps", "title"), fields, "三类变更全报（02 契约字段级）");
    }

    @Test
    void diff_显式基准版本_跨版本比较() {
        long pid = createVersionLineProject("spv5");
        CaseInfo info = createCase(pid);
        scriptService.save(new ScriptSaveInput(info.id(), "v2", actionJson("登录A", STEP_A, "{\"ENV\":\"dev\"}"), 1));
        caseService.update(1L, info.id(), new CaseUpdateInput("登录功能测试改", null, null,
                null, null, null, null, null, null, null), 1);
        scriptService.save(new ScriptSaveInput(info.id(), "v2", actionJson("登录A", STEP_A, "{\"ENV\":\"prod\"}"), 1));

        ScriptVersionDiff diff = scriptService.diffVersion(info.id(), 2, 1);
        assertEquals(1, diff.fromVersion());
        assertEquals(2, diff.toVersion());
        assertEquals(1, diff.changes().size(), "仅 env 变更");
        assertEquals("env", diff.changes().get(0).field());
        assertNotNull(diff.changes().get(0).label());
    }

    @Test
    void rollback_快照完整性_历史不删且当前指针等旧版副本() {
        long pid = createVersionLineProject("spv6");
        CaseInfo info = createCase(pid);
        scriptService.save(new ScriptSaveInput(info.id(), "v2", actionJson("登录A", STEP_A, "{\"ENV\":\"dev\"}"), 1));
        caseService.update(1L, info.id(), new CaseUpdateInput("登录功能测试改", null, null,
                null, null, null, null, null, null, null), 1);
        scriptService.save(new ScriptSaveInput(info.id(), "v2", actionJson("登录B", STEP_B, "{\"ENV\":\"dev\"}"), 1));
        caseService.update(1L, info.id(), new CaseUpdateInput("登录功能测试再改", null, null,
                null, null, null, null, null, null, null), 2);
        scriptService.save(new ScriptSaveInput(info.id(), "v2", actionJson("登录C", STEP_B, "{\"ENV\":\"test\"}"), 1));

        caseService.rollback(1L, info.id(), 2, 3);

        assertEquals(4, snapshotCount(info.id()), "回滚不清史+追加重挂快照（副本语义）");
        List<ScriptVersionInfo> versions = scriptService.listVersions(info.id());
        assertEquals(4, versions.size());
        ScriptVersionInfo current = versions.stream()
                .filter(s -> ScriptVersionSupport.REASON_ROLLBACK_RESTORE.equals(s.reason()))
                .findFirst().orElseThrow(() -> new AssertionError("缺回滚重挂快照行: " + versions));
        assertEquals(4, current.caseVersion(), "回滚产生用例 v4，重挂快照对齐 v4（坑 b）");
        assertEquals(4, current.scriptVersion(), "upsert 分支 tp_script.version+1=4");
        String curTitle = jdbcTemplate.queryForObject(
                "SELECT action_json #>> '{title}' FROM tp_script WHERE case_id = ?",
                String.class, info.id());
        assertEquals("登录A", curTitle, "当前指针=回滚目标快照的脚本副本（TC-B5-02）");
    }

    @Test
    void rollback_脚本删除场景_历史版本线不分裂() {
        long pid = createVersionLineProject("spv7");
        CaseInfo info = createCase(pid);
        scriptService.save(new ScriptSaveInput(info.id(), "v2", actionJson("登录A", STEP_A, "{\"ENV\":\"dev\"}"), 1));
        caseService.update(1L, info.id(), new CaseUpdateInput("登录功能测试改", null, null,
                null, null, null, null, null, null, null), 1);
        jdbcTemplate.update("UPDATE tp_case_version SET snapshot_json = "
                + "jsonb_set(snapshot_json, '{script}', 'null') WHERE case_id = ? AND version = 1", info.id());

        caseService.rollback(1L, info.id(), 1, 2);

        Integer scriptRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_script WHERE case_id = ?", Integer.class, info.id());
        assertEquals(0, scriptRows, "回滚到无脚本→tp_script 行删除（既有 restoreScript 语义）");
        List<ScriptVersionInfo> versions = scriptService.listVersions(info.id());
        assertEquals(1, versions.size(), "版本线不分裂：tp_script 行已删历史快照仍按 case_id 可查");
        assertEquals("登录A", jdbcTemplate.queryForObject(
                "SELECT actions_json #>> '{title}' FROM tp_script_version WHERE case_id = ? AND version = 1",
                String.class, info.id()));
    }

    @Test
    void 无脚本用例_版本操作不阻断() {
        long pid = createVersionLineProject("spv8");
        CaseInfo info = createCase(pid);
        caseService.update(1L, info.id(), new CaseUpdateInput("登录功能测试改", null, null,
                null, null, null, null, null, null, null), 1);

        assertEquals(List.of(), scriptService.listVersions(info.id()), "无快照=空列表");
        caseService.rollback(1L, info.id(), 1, 2);
        CaseInfo after = caseService.get(info.id());
        assertEquals(3, after.version(), "无脚本用例回滚不阻断");
    }

    @Test
    void 损坏快照_diff标记不崩() {
        long pid = createVersionLineProject("spv9");
        CaseInfo info = createCase(pid);
        scriptService.save(new ScriptSaveInput(info.id(), "v2", actionJson("登录A", STEP_A, "{\"ENV\":\"dev\"}"), 1));
        caseService.update(1L, info.id(), new CaseUpdateInput("登录功能测试改", null, null,
                null, null, null, null, null, null, null), 1);
        scriptService.save(new ScriptSaveInput(info.id(), "v2", actionJson("登录B", STEP_A, "{\"ENV\":\"dev\"}"), 1));
        jdbcTemplate.update("UPDATE tp_script_version SET actions_json = '123'::jsonb "
                + "WHERE case_id = ? AND version = 1", info.id());

        ScriptVersionDiff diff = scriptService.diffVersion(info.id(), 2, 1);
        assertTrue(diff.corrupted(), "损坏侧快照→标记不可 diff");
        assertEquals(List.of(), diff.changes());
        assertNotNull(diff.corruptedMessage());
        assertFalse(diff.corruptedMessage().isBlank(), "人话说明（前端直接展示）");
    }

    @Test
    void diff_目标版本快照不存在_404人话() {
        long pid = createVersionLineProject("spv10");
        CaseInfo info = createCase(pid);
        scriptService.save(new ScriptSaveInput(info.id(), "v2", actionJson("登录A", STEP_A, "{\"ENV\":\"dev\"}"), 1));
        ServiceException e = assertThrows(ServiceException.class,
                () -> scriptService.diffVersion(info.id(), 9, null));
        assertEquals(40401, e.getCode());
    }

    @Test
    void purge项目_脚本版本快照随用例物理删除_零残留() {
        long pid = createVersionLineProject("spv11");
        CaseInfo info = createCase(pid);
        scriptService.save(new ScriptSaveInput(info.id(), "v2", actionJson("登录A", STEP_A, "{\"ENV\":\"dev\"}"), 1));
        assertEquals(1, snapshotCount(info.id()), "前置：保存脚本已写版本快照");

        projectService.purge(1L, pid, true);
        assertEquals(0, snapshotCount(info.id()), "purge 后脚本版本快照必须物理删除（零孤儿，镜像 T111 先例）");
    }
}
