package com.sixbean.testpike.core.sample.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.sample.api.dto.WordingAdoptionStat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class PromptAdoptionSummaryServiceTest {

    
    private static final long SEG_LO = 96300;
    private static final long SEG_HI = 96399;

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
                projectId, "汇总项目" + code, code);
    }

    private void seedCase(long caseId, long projectId, int deleted) {
        jdbcTemplate.update("INSERT INTO tp_test_case (id, case_no, project_id, title, priority, deleted, "
                + "created_at, updated_at) OVERRIDING SYSTEM VALUE "
                + "VALUES (?, ?, ?, '汇总夹具单', 1, ?, now(), now())",
                caseId, "SUM-" + caseId, projectId, deleted);
    }

    private void seedSample(long caseId, String sampleType, int adopted, String json) {
        jdbcTemplate.update("INSERT INTO tp_training_sample (case_id, sample_type, sample_json, source_version, "
                + "adopted) VALUES (?, ?, ?::jsonb, 1, ?)", caseId, sampleType, json, adopted);
    }

    private static String wordingRejectJson() {
        return "{\"review\":{\"remark\":\"表述太啰嗦\",\"feedbackTypes\":[\"措辞不准确\"]}}";
    }

    private static String otherRejectJson() {
        return "{\"review\":{\"remark\":\"步骤不对\",\"feedbackTypes\":[\"步骤不准确\"]}}";
    }

    private WordingAdoptionStat statOf(List<WordingAdoptionStat> stats, long projectId) {
        return stats.stream().filter(s -> s.projectId() == projectId).findFirst().orElseThrow();
    }

    @Test
    void 按项目聚合_已采纳措辞驳回与配对差异计数() {
        seedProject(96301, "SUM-A");
        seedCase(96311, 96301, 0);
        seedCase(96312, 96301, 0);
        seedSample(96311, "驳回", 1, wordingRejectJson());
        seedSample(96311, "修改差异", 1, "{\"changes\":[]}");
        seedSample(96312, "驳回", 1, wordingRejectJson());

        List<WordingAdoptionStat> stats = trainingSampleService.summaryWordingAdoption();
        assertTrue(!stats.isEmpty(), "有已采纳措辞样本的项目必须出行：" + stats);
        WordingAdoptionStat a = statOf(stats, 96301L);
        assertEquals(2L, a.wordingRejects(), "已采纳措辞驳回样本计 2");
        assertEquals(1L, a.adoptedDiffs(), "配对已采纳修改差异样本计 1");
    }

    @Test
    void 撤销_非措辞_软删用例_悬空样本均不计量() {
        seedProject(96302, "SUM-B");
        seedCase(96321, 96302, 0);
        seedCase(96322, 96302, 1);
        seedSample(96321, "驳回", 0, wordingRejectJson());
        seedSample(96321, "驳回", 1, otherRejectJson());
        seedSample(96322, "驳回", 1, wordingRejectJson());
        seedSample(96398, "驳回", 1, wordingRejectJson());
        seedSample(96321, "修改差异", 1, "{\"changes\":[]}");

        List<WordingAdoptionStat> stats = trainingSampleService.summaryWordingAdoption();
        assertTrue(stats.stream().noneMatch(s -> s.projectId() == 96302L),
                "96302 无任何计入行=零措辞项目不出行（日志面禁空报噪声）：" + stats);
    }
}
