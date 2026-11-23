package com.sixbean.testpike.core.sample.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.sample.api.dto.TrainingSampleItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class TrainingSampleServiceTest {

    
    private static final long SEG_LO = 96100;
    private static final long SEG_HI = 96199;

    @Autowired
    private TrainingSampleService trainingSampleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_training_sample WHERE case_id BETWEEN ? AND ?", SEG_LO, SEG_HI);
        jdbcTemplate.update("DELETE FROM tp_case_step WHERE case_id BETWEEN ? AND ?", SEG_LO, SEG_HI);
        jdbcTemplate.update("DELETE FROM tp_test_case WHERE id BETWEEN ? AND ?", SEG_LO, SEG_HI);
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id BETWEEN ? AND ?", SEG_LO, SEG_HI);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id BETWEEN ? AND ?", SEG_LO, SEG_HI);
    }

    private void seedProject(long projectId, String code) {
        jdbcTemplate.update("INSERT INTO tp_project (id, name, code) OVERRIDING SYSTEM VALUE VALUES (?, ?, ?)",
                projectId, "样本项目" + code, code);
    }

    private void seedCase(long caseId, long projectId, String caseNo, int deleted) {
        jdbcTemplate.update("""
                INSERT INTO tp_test_case (id, case_no, project_id, title, priority, deleted,
                                          created_at, updated_at)
                OVERRIDING SYSTEM VALUE
                VALUES (?, ?, ?, '样本夹具单', 1, ?, now(), now())
                """, caseId, caseNo, projectId, deleted);
    }

    private long seedSample(long caseId, String sampleType, int sourceVersion, String json) {
        jdbcTemplate.update("INSERT INTO tp_training_sample (case_id, sample_type, sample_json, source_version) "
                + "VALUES (?, ?, ?::jsonb, ?)", caseId, sampleType, json, sourceVersion);
        return jdbcTemplate.queryForObject(
                "SELECT max(id) FROM tp_training_sample WHERE case_id = ?", Long.class, caseId);
    }

    @Test
    void 分页_id倒序_total独立于页大小_字段透传() {
        seedProject(96101, "SMP-A1");
        seedCase(96111, 96101, "SMC-001", 0);
        seedSample(96111, "驳回", 3, "{\"reason\":\"步骤不准确\"}");
        seedSample(96111, "修改差异", 4, "{\"diff\":\"title\"}");
        seedSample(96111, "驳回", 5, "{}");

        PageResult<TrainingSampleItem> page1 = trainingSampleService.list(null, null, 1, 2);
        List<TrainingSampleItem> head = page1.list();
        assertEquals(2, head.size());
        assertTrue(head.get(0).id() > head.get(1).id(), "id DESC 最新在前");
        PageResult<TrainingSampleItem> page2 = trainingSampleService.list(null, null, 2, 2);
        assertTrue(page1.total() >= 3, "total ≥ 夹具 3 行且不受 size 影响");

        TrainingSampleItem top = head.get(0);
        assertEquals("驳回", top.sampleType());
        assertEquals(5, top.sourceVersion());
        assertNotNull(top.sampleJson(), "样本 JSON ::text 透传");
        assertNotNull(top.createdAt());
        assertEquals(96111L, top.caseId());
        assertEquals("SMC-001", top.caseNo(), "关联用例号随行透出");
        assertEquals(96101L, top.projectId(), "项目维经用例关联推导");
    }

    @Test
    void 筛选项目维_经用例关联_已删用例不算命中() {
        seedProject(96102, "SMP-B1");
        seedProject(96103, "SMP-B2");
        seedCase(96121, 96102, "SMB-001", 0);
        seedCase(96122, 96103, "SMB-002", 0);
        seedCase(96123, 96103, "SMB-003", 1);
        seedSample(96121, "驳回", 1, "{}");
        seedSample(96122, "修改差异", 2, "{}");
        seedSample(96123, "驳回", 1, "{}");

        PageResult<TrainingSampleItem> pB1 = trainingSampleService.list(96102L, null, 1, 20);
        assertEquals(1L, pB1.total());
        assertEquals(96121L, pB1.list().get(0).caseId());

        PageResult<TrainingSampleItem> pB2 = trainingSampleService.list(96103L, null, 1, 20);
        assertEquals(1L, pB2.total(), "已删用例（deleted=1）的样本不算项目命中");
        assertEquals("修改差异", pB2.list().get(0).sampleType());

        PageResult<TrainingSampleItem> combo = trainingSampleService.list(96102L, "驳回", 1, 20);
        assertEquals(1L, combo.total());
        PageResult<TrainingSampleItem> none = trainingSampleService.list(96102L, "修改差异", 1, 20);
        assertEquals(0L, none.total());
    }

    @Test
    void 悬空样本_全量视图可见_关联字段空() {
        seedSample(96198, "驳回", 1, "{\"orphan\":true}");
        PageResult<TrainingSampleItem> all = trainingSampleService.list(null, null, 1, 200);
        TrainingSampleItem orphan = all.list().stream()
                .filter(i -> i.caseId() == 96198L).findFirst().orElseThrow();
        assertNull(orphan.caseNo(), "悬空样本用例号空");
        assertNull(orphan.projectId(), "悬空样本项目维空");
        assertTrue(orphan.sampleJson() != null && orphan.sampleJson().contains("orphan"),
                "JSON ::text 透传非 null（jsonb 规范化空格不逐字断言）");
        assertTrue(trainingSampleService.list(96109L, null, 1, 200).list().stream()
                .noneMatch(i -> i.caseId() == 96198L));
    }

    @Test
    void 样本类型非法值_40001人话点名两枚举() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> trainingSampleService.list(null, "编辑", 1, 20));
        assertTrue(ex.getMessage().contains("驳回") && ex.getMessage().contains("修改差异"),
                "人话点名值域清单：" + ex.getMessage());
    }


    private long seedCaseIn(long caseId, long projectId) {
        seedProject(projectId, "WD-" + projectId);
        seedCase(caseId, projectId, "WDC-" + caseId, 0);
        return caseId;
    }

    private long seedWordReject(long caseId, String remark) {
        return seedSample(caseId, "驳回", 1,
                "{\"review\":{\"remark\":\"" + remark + "\",\"feedbackTypes\":[\"措辞不准确\"]}}");
    }

    private long seedOtherReject(long caseId) {
        return seedSample(caseId, "驳回", 1,
                "{\"review\":{\"remark\":\"步骤跳号\",\"feedbackTypes\":[\"步骤不准确\"]}}");
    }

    private long seedDiff(long caseId, String after) {
        return seedSample(caseId, "修改差异", 2,
                "{\"changes\":[{\"field\":\"expectedResult\",\"label\":\"预期结果\","
                        + "\"before\":\"正常\",\"after\":\"" + after + "\"}]}");
    }

    
    @Test
    void 措辞对读路径_WORDING驳回成对_非WORDING不取_撤销排除_再采纳恢复() {
        long p = 96131;
        long c1 = seedCaseIn(96141, p);
        long reject = seedWordReject(c1, "预期结果应写「返回 200 且 code=0」");
        long diff = seedDiff(c1, "返回 200 且 body.code=0");
        seedOtherReject(c1);
        long c2 = seedCaseIn(96142, 96132L);
        seedWordReject(c2, "别项目的措辞反馈");

        var pairs = trainingSampleService.listWordingPairs(p, 20);
        assertEquals(1, pairs.size(), "仅 WORDING 驳回样本成对，项目隔离：project=" + p);
        var pair = pairs.get(0);
        assertEquals(reject, pair.rejectSampleId());
        assertEquals(diff, pair.diffSampleId(), "附同用例修改差异样本（after 措辞对来源）");
        assertEquals("预期结果应写「返回 200 且 code=0」", pair.remark());
        assertTrue(pair.diffChangesJson().contains("返回 200 且 body.code=0"),
                "diff changes 原文透传：" + pair.diffChangesJson());

        trainingSampleService.updateAdoption(reject, 0);
        assertTrue(trainingSampleService.listWordingPairs(p, 20).isEmpty(),
                "撤销后不再注入候选（adopted=0 排除）");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT adopted FROM tp_training_sample WHERE id = ?", Integer.class, reject));
        trainingSampleService.updateAdoption(reject, 1);
        assertEquals(1, trainingSampleService.listWordingPairs(p, 20).size(), "再采纳恢复");
    }

    
    @Test
    void 措辞对读路径_差异行撤销_驳回行保留空配对() {
        long p = 96133;
        long c = seedCaseIn(96143, p);
        seedWordReject(c, "标题措辞应含模块名");
        long diff = seedDiff(c, "登录模块-手机号校验通过");
        trainingSampleService.updateAdoption(diff, 0);

        var pairs = trainingSampleService.listWordingPairs(p, 20);
        assertEquals(1, pairs.size(), "驳回行已采纳=配对仍在，差异行撤销只置空差异链路");
        assertNull(pairs.get(0).diffSampleId(), "差异行 adopted=0 → diffSampleId 空");
    }

    
    @Test
    void 采纳写路径_值域闸与存在性闸() {
        long p = 96134;
        long c = seedCaseIn(96144, p);
        long reject = seedWordReject(c, "预期结果措辞反馈");

        ValidationException bad = assertThrows(ValidationException.class,
                () -> trainingSampleService.updateAdoption(reject, 2));
        assertTrue(bad.getMessage().contains("1") && bad.getMessage().contains("0"),
                "人话点名值域：" + bad.getMessage());
        assertThrows(ServiceException.class,
                () -> trainingSampleService.updateAdoption(999999999L, 0));
    }

    
    @Test
    void 台账列表透出采纳留痕列() {
        long p = 96135;
        long c = seedCaseIn(96145, p);
        long s = seedSample(c, "驳回", 1, "{\"review\":{\"remark\":\"x\",\"feedbackTypes\":[]}}");
        trainingSampleService.updateAdoption(s, 0);
        var row = trainingSampleService.list(p, null, 1, 20).list().stream()
                .filter(i -> i.id().equals(s)).findFirst().orElseThrow();
        assertEquals(0, row.adopted(), "台账行透出 adopted 留痕");
    }

    // ---- page 17: server-side sort over the sample ledger ----

    /**
     * RED anchor: a whitelisted key orders the whole filtered window in SQL
     * (ascending here), and rows that tie on the sort column keep the pinned
     * id DESC tie-break so paging stays deterministic. Fixture 961xx segment.
     */
    @Test
    void 排序白名单列升序_平局行_id倒序兜底() {
        long p = 96151;
        long c = seedCaseIn(96152, p);
        long v1a = seedSample(c, "驳回", 1, "{}");
        long v3 = seedSample(c, "修改差异", 3, "{}");
        long v1b = seedSample(c, "驳回", 1, "{}");

        List<TrainingSampleItem> asc = trainingSampleService
                .list(p, null, 1, 20, "source_version", "asc").list();
        List<Long> ascIds = asc.stream().map(TrainingSampleItem::id).toList();
        assertEquals(List.of(v1b, v1a, v3), ascIds,
                "source_version ASC with id DESC tie-break among the fixture slice");

        List<TrainingSampleItem> desc = trainingSampleService
                .list(p, null, 1, 20, "source_version", "desc").list();
        assertEquals(v3, desc.get(0).id(), "DESC puts the largest version first");
    }

    /**
     * RED anchor: the legacy four-argument entry and an explicit null sort
     * pair both keep the historical id DESC default — zero drift for every
     * existing caller of the ledger.
     */
    @Test
    void 排序键缺省_零漂移沿用历史id倒序() {
        long p = 96153;
        long c = seedCaseIn(96154, p);
        seedSample(c, "驳回", 1, "{}");
        seedSample(c, "修改差异", 2, "{}");

        List<Long> legacy = trainingSampleService.list(p, null, 1, 20).list().stream()
                .map(TrainingSampleItem::id).toList();
        List<Long> explicitNull = trainingSampleService
                .list(p, null, 1, 20, null, null).list().stream()
                .map(TrainingSampleItem::id).toList();
        List<Long> blankKey = trainingSampleService
                .list(p, null, 1, 20, "", "").list().stream()
                .map(TrainingSampleItem::id).toList();
        assertEquals(legacy, explicitNull, "null sort pair matches the legacy order");
        assertEquals(legacy, blankKey, "blank sort pair matches the legacy order");
        assertTrue(legacy.get(0) > legacy.get(1), "historical default stays id DESC");
    }

    /**
     * RED anchor: an off-list or injection-shaped key is refused fail-closed
     * before any database read, with a human message naming the allowed set
     * (shared SortColumnPolicy.require posture, page-16 precedent: the thrown
     * type is ServiceException, not ValidationException).
     */
    @Test
    void 排序键白名单外_人话点名允许集() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> trainingSampleService.list(null, null, 1, 20, "id;DROP TABLE", "asc"));
        assertTrue(ex.getMessage().contains("source_version")
                        && ex.getMessage().contains("created_at"),
                "message names the allowed columns: " + ex.getMessage());
        assertThrows(ServiceException.class,
                () -> trainingSampleService.list(null, null, 1, 20, "sample_json", "asc"),
                "sample_json is not rendered as a column and stays off the whitelist");
    }
}
