package com.sixbean.testpike.core.tool.domain.component;

import com.sixbean.testpike.common.genkit.GenFunction;
import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.domain.service.ToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class GenkitToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GenkitToolSeedInitializer.class);

    
    static final List<String> TOP10_FUNCTIONS = List.of(
            "idCard", "bankCard", "uscc", "phone", "email",
            "md5", "sha256", "base64Encode", "dateTime", "jsonFormat");

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;
    
    private static final String SCHEMA_RANDOM =
            "{\"type\":\"object\",\"properties\":{\"seed\":{\"type\":\"integer\","
                    + "\"description\":\"显式随机种子（缺省=系统随机，同种子可复现）\"}}}";
    private static final String SCHEMA_TEXT =
            "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\","
                    + "\"description\":\"待处理原文\"}},\"required\":[\"text\"]}";
    private static final String SCHEMA_DATETIME =
            "{\"type\":\"object\",\"properties\":{\"format\":{\"type\":\"string\","
                    + "\"description\":\"日期格式（缺省 yyyy-MM-dd HH:mm:ss）\"}}}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public GenkitToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置造数工具 {} 件（genkit Top10，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            log.warn("内置造数工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    
    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'function' FROM tp_tool WHERE exec_kind = 4",
                String.class);
        int created = 0;
        for (String fn : TOP10_FUNCTIONS) {
            if (present.contains(fn)) {
                continue;
            }
            registerPublished(fn);
            created++;
        }
        return created;
    }

    private void registerPublished(String functionName) {
        GenFunction gf = GenFunction.byName(functionName);
        if (gf == null) {
            log.warn("内置造数函数 {} 不在 genkit 注册表，跳过种子注册", functionName);
            return;
        }
        String name = "造数·" + describe(gf);
        String spec = "{\"function\":\"" + functionName + "\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, name, 2, "造数", gf.description(), null,
                "[\"造数\",\"内置\"]", inputSchema(gf), null,
                ToolExecKind.GENKIT.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }

    
    private String describe(GenFunction gf) {
        String desc = gf.description();
        int cut = desc.indexOf('（');
        String shortName = cut > 0 ? desc.substring(0, cut) : desc;
        return shortName.length() > 20 ? shortName.substring(0, 20) : shortName;
    }

    private String inputSchema(GenFunction gf) {
        if (gf == GenFunction.DATE_TIME) {
            return SCHEMA_DATETIME;
        }
        return gf.isRandom() ? SCHEMA_RANDOM : SCHEMA_TEXT;
    }
}
