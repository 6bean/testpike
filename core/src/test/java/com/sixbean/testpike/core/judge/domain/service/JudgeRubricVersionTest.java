package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.judge.api.dto.JudgeRuleItem;
import com.sixbean.testpike.core.judge.api.dto.RubricDimension;
import com.sixbean.testpike.core.judge.api.dto.RubricSaveInput;
import com.sixbean.testpike.core.judge.api.dto.RubricView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class JudgeRubricVersionTest {

    private static final String FIXTURE_LIKE = "t278j1.%";

    @Autowired
    private JudgeRubricService judgeRubricService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_judge_rubric WHERE name LIKE ?", FIXTURE_LIKE);
    }

    private RubricSaveInput input(String name, int passScore, BigDecimal wRule, BigDecimal wLlm) {
        return new RubricSaveInput(name, "用例生成",
                List.of(new RubricDimension("相关性", "输出与需求的关联程度", 0.6,
                                List.of("9 分长这样：逐条对应需求点", "3 分长这样：答非所问")),
                        new RubricDimension("完整性", "覆盖全部验收点", 0.4, List.of())),
                List.of(new JudgeRuleItem("json-valid", Map.of("strict", true))),
                passScore, wRule, wLlm, 9101L);
    }

    @Test
    void 首次保存_版本1_默认灰度_维度规则权重全字段往返() {
        RubricView saved = judgeRubricService.save(input("t278j1.first", 60,
                new BigDecimal("0.400"), new BigDecimal("0.600")));

        assertNotNull(saved.id());
        assertEquals(1, saved.version());
        assertEquals(1, saved.status(), "新版本默认状态=灰度（C1 同机制）");
        assertEquals(2, saved.dimensions().size());
        assertEquals("相关性", saved.dimensions().get(0).name());
        assertEquals(2, saved.dimensions().get(0).anchorExamples().size(), "评分锚点 few-shot 必须往返");
        assertTrue(saved.dimensions().get(1).anchorExamples().isEmpty());
        assertEquals(1, saved.rules().size());
        assertEquals("json-valid", saved.rules().get(0).checker());
        assertEquals(Boolean.TRUE, saved.rules().get(0).params().get("strict"));
        assertEquals(60, saved.passScore());
        assertEquals(0, new BigDecimal("0.400").compareTo(saved.wRule()));
        assertEquals("t278j1.first", saved.name());
    }

    @Test
    void 同族再存_版本递增_旧行原文不动() {
        RubricView v1 = judgeRubricService.save(input("t278j1.bump", 60,
                new BigDecimal("0.500"), new BigDecimal("0.500")));
        RubricView v2 = judgeRubricService.save(input("t278j1.bump", 80,
                new BigDecimal("0.300"), new BigDecimal("0.700")));

        assertEquals(2, v2.version());
        RubricView v1Pinned = judgeRubricService.detail(v1.id());
        assertEquals(60, v1Pinned.passScore(), "修改即新版本：旧行永不 UPDATE（钉住不回拽）");
        assertEquals(new BigDecimal("0.500").compareTo(v1Pinned.wRule()), 0);
        List<RubricView> versions = judgeRubricService.listVersions("t278j1.bump", "用例生成");
        assertEquals(List.of(2, 1), versions.stream().map(RubricView::version).toList(), "版本列表倒序");
    }

    @Test
    void 克隆指定版本_内容复制为新版本行() {
        RubricView v1 = judgeRubricService.save(input("t278j1.clone", 60,
                new BigDecimal("0.400"), new BigDecimal("0.600")));
        judgeRubricService.save(input("t278j1.clone", 80,
                new BigDecimal("0.300"), new BigDecimal("0.700")));

        RubricView cloned = judgeRubricService.cloneVersion(v1.id(), 9102L);

        assertEquals(3, cloned.version(), "克隆即新版本（§6.2 C1 同机制），族内继续递增");
        assertEquals(60, cloned.passScore());
        assertEquals(v1.dimensions().size(), cloned.dimensions().size());
        assertEquals(v1.dimensions().get(0).name(), cloned.dimensions().get(0).name());
        assertEquals(v1.dimensions().get(0).anchorExamples(), cloned.dimensions().get(0).anchorExamples());
    }

    @Test
    void 阈值与权重越界与必填缺失_人话点名40001() {
        ValidationException e1 = assertThrows(ValidationException.class, () -> judgeRubricService.save(
                input("t278j1.bad", 101, new BigDecimal("0.400"), new BigDecimal("0.600"))));
        assertTrue(e1.getMessage().contains("门禁阈值"), e1.getMessage());

        assertThrows(ValidationException.class, () -> judgeRubricService.save(
                input("t278j1.bad", 60, new BigDecimal("1.500"), new BigDecimal("0.600"))));

        assertThrows(ValidationException.class, () -> judgeRubricService.save(
                new RubricSaveInput("  ", "用例生成", List.of(), List.of(), 60,
                        new BigDecimal("0.400"), new BigDecimal("0.600"), 1L)));
    }

    @Test
    void 迁移落库实证_五表在位() {
        for (String table : List.of("tp_judge_rubric", "tp_judge_sample", "tp_judge_run",
                "tp_judge_record", "tp_judge_review")) {
            Integer c = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
                    Integer.class, table);
            assertEquals(1, c, "V208 应落表 " + table);
        }
    }
}
