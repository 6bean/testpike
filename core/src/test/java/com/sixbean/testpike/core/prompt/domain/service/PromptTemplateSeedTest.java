package com.sixbean.testpike.core.prompt.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class PromptTemplateSeedTest {

 
    private static final Map<String, String> FACTORY_SEEDS = Map.of(
            "case-gen-prompt", "用例生成",
            "script-gen-prompt", "用例生成",
            "review-prompt", "评审",
            "defect-classify-prompt", "缺陷归因",
            "perf-scenario-prompt", "压测场景");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Map<String, Object> seedRow(String name, String domain) {
        return jdbcTemplate.queryForMap(
                "SELECT version, status, content FROM tp_prompt_template"
                        + " WHERE name = ? AND domain = ? AND version = 1", name, domain);
    }

    @Test
    void 出厂五组种子行在位_版本1默认灰度() {
        for (Map.Entry<String, String> seed : FACTORY_SEEDS.entrySet()) {
            Map<String, Object> row = seedRow(seed.getKey(), seed.getValue());
            assertEquals(1, ((Number) row.get("version")).intValue(),
                    seed.getKey() + " 出厂版本=1");
            assertEquals(1, ((Number) row.get("status")).intValue(),
                    seed.getKey() + " 出厂默认灰度态（零流量不消费，晋升走管理页）");
            assertNotNull(row.get("content"));
        }
    }

    @Test
    void 用例生成族content承载分流标记与占位符() {
        String stage1 = (String) seedRow("case-gen-prompt", "用例生成").get("content");
        assertTrue(stage1.contains("请针对以下需求生成 1 条结构化测试用例"),
                "stage1 分流标记字面禁改写（桩分流/L3 抄录判定锚）");
        for (String ph : new String[]{"{{requirement}}", "{{acceptanceCriteria}}", "{{knowledge}}",
                "{{fewshot}}", "{{wording}}", "{{lang}}", "{{feedback}}"}) {
            assertTrue(stage1.contains(ph), "stage1 占位符缺失: " + ph);
        }

        String stage2 = (String) seedRow("script-gen-prompt", "用例生成").get("content");
        assertTrue(stage2.contains("请将以下测试用例转换为动作 JSON"),
                "stage2 分流标记字面禁改写");
        for (String ph : new String[]{"{{caseBlock}}", "{{scriptLimits}}", "{{feedback}}"}) {
            assertTrue(stage2.contains(ph), "stage2 占位符缺失: " + ph);
        }
        assertTrue(stage2.contains("04 契约"), "stage2 契约引用锚在位");
    }

    @Test
    void 非生成三族为可读资产骨架() {
        for (String name : new String[]{"review-prompt", "defect-classify-prompt",
                "perf-scenario-prompt"}) {
            String content = (String) seedRow(name, FACTORY_SEEDS.get(name)).get("content");
            assertTrue(content.length() > 40, name + " 出厂骨架应为可读人话文本");
        }
    }
}
