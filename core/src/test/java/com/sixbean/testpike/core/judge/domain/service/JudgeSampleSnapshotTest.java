package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.judge.api.dto.EvalSetVersionView;
import com.sixbean.testpike.core.judge.api.dto.SampleInput;
import com.sixbean.testpike.core.judge.api.dto.SampleView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class JudgeSampleSnapshotTest {

    private static final String FIXTURE_LIKE = "t278j1s.%";

    @Autowired
    private JudgeSampleService judgeSampleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_judge_sample WHERE set_name LIKE ?", FIXTURE_LIKE);
    }

    private SampleInput sample(String input, String expected, List<String> refs, String source, int sensitive) {
        return new SampleInput(input, expected, refs, source, sensitive);
    }

    @Test
    void 首发评测集_版本1_全行同快照锚_字段往返() {
        EvalSetVersionView pub = judgeSampleService.publish("t278j1s.basic", List.of(
                sample("需求A", "用例A", List.of("参考1", "参考2"), "manual", 0),
                sample("需求B", "用例B", List.of(), "ai-generated", 1)), 9101L);

        assertEquals("t278j1s.basic", pub.setName());
        assertEquals(1, pub.version());
        assertEquals(2, pub.sampleCount());
        assertTrue(pub.snapshotDigest().matches("[0-9a-f]{64}"), "锚=SHA-256 小写 hex");

        List<SampleView> rows = judgeSampleService.listSamples("t278j1s.basic", 1);
        assertEquals(2, rows.size());
        for (SampleView row : rows) {
            assertEquals(pub.snapshotDigest(), row.snapshotDigest(), "同版本全行同锚");
        }
        SampleView s = rows.stream().filter(r -> "需求A".equals(r.input())).findFirst().orElseThrow();
        assertEquals(List.of("参考1", "参考2"), s.referenceDocs());
        assertEquals("manual", s.source());
        assertEquals(0, s.sensitive());
        SampleView s2 = rows.stream().filter(r -> "需求B".equals(r.input())).findFirst().orElseThrow();
        assertEquals(1, s2.sensitive(), "敏感位往返（外部厂商闸的数据前提）");
    }

    @Test
    void 增样本即新版本_旧版本钉住不动() {
        EvalSetVersionView v1 = judgeSampleService.publish("t278j1s.bump", List.of(
                sample("需求A", "用例A", List.of(), "manual", 0)), 9101L);
        EvalSetVersionView v2 = judgeSampleService.publish("t278j1s.bump", List.of(
                sample("需求A", "用例A", List.of(), "manual", 0),
                sample("需求B", "用例B", List.of(), "manual", 0)), 9101L);

        assertEquals(2, v2.version());
        assertNotEquals(v1.snapshotDigest(), v2.snapshotDigest(), "内容变=锚变");
        assertEquals(1, judgeSampleService.listSamples("t278j1s.bump", 1).size(),
                "旧版本行不被拽动（JudgeRun 绑版本可追溯的存储前提）");
        assertEquals(v1.snapshotDigest(),
                judgeSampleService.listSamples("t278j1s.bump", 1).get(0).snapshotDigest());
    }

    @Test
    void 同内容乱序发布_锚相同() {
        EvalSetVersionView a = judgeSampleService.publish("t278j1s.order1", List.of(
                sample("需求A", "用例A", List.of("r1"), "manual", 0),
                sample("需求B", "用例B", List.of("r2"), "batch-export", 1)), 9101L);
        EvalSetVersionView b = judgeSampleService.publish("t278j1s.order2", List.of(
                sample("需求B", "用例B", List.of("r2"), "batch-export", 1),
                sample("需求A", "用例A", List.of("r1"), "manual", 0)), 9101L);

        assertEquals(a.snapshotDigest(), b.snapshotDigest(),
                "锚=样本规范串排序拼接（插入顺序不影响版本身份）");
    }

    @Test
    void 空集与非法来源与未知集_人话点名() {
        assertThrows(ValidationException.class,
                () -> judgeSampleService.publish("t278j1s.bad", List.of(), 9101L));

        ValidationException e2 = assertThrows(ValidationException.class,
                () -> judgeSampleService.publish("t278j1s.bad", List.of(
                        sample("x", "y", List.of(), "teleport", 0)), 9101L));
        assertTrue(e2.getMessage().contains("来源"), e2.getMessage());

        assertThrows(ValidationException.class,
                () -> judgeSampleService.listSamples("t278j1s.不存在", 1));
    }
}
