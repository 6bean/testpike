package com.sixbean.testpike.aiservice.gen.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;


public class PromptTemplateResolver {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateResolver.class);

    
    public static final String TEMPLATE_DOMAIN = "用例生成";
    
    public static final String STAGE1_TEMPLATE_NAME = "case-gen-prompt";
    
    public static final String STAGE2_TEMPLATE_NAME = "script-gen-prompt";
    
    public static final String TRAFFIC_PCT_KEY = "prompt.gen.traffic-pct";
    
    static final int STATUS_GRAY = 1;
    
    static final int STATUS_FULL = 2;

    private final CoreGateway coreGateway;

    public PromptTemplateResolver(CoreGateway coreGateway) {
        this.coreGateway = coreGateway;
    }

    
    public java.util.Optional<CoreGateway.PromptTemplateBrief> selectTemplate(
            String name, long projectId, long taskId) {
        return selectTemplate(name, projectId, taskId, true);
    }

    
    public java.util.Optional<CoreGateway.PromptTemplateBrief> selectTemplate(
            String name, long projectId, long taskId, boolean pin) {
        try {
            Long pinned = coreGateway.getTaskTemplatePin(taskId);
            if (pinned != null && pinned > 0) {
                CoreGateway.PromptTemplateBrief row = coreGateway.promptTemplateDetail(pinned);
                if (row == null || row.content() == null || row.content().isBlank()) {
                    return java.util.Optional.empty();
                }
                if (name.equals(row.name())) {
                    return java.util.Optional.of(row);
                }
            }
            int pct = com.sixbean.testpike.aiservice.llm.domain.component
                    .SysConfigInts.parseInt(coreGateway.getSysConfig(TRAFFIC_PCT_KEY), 0);
            boolean grayHit = Math.floorMod(projectId, 100) < pct;
            CoreGateway.PromptTemplateBrief brief =
                    coreGateway.promptTemplateLatestByStatus(name, TEMPLATE_DOMAIN,
                            grayHit ? STATUS_GRAY : STATUS_FULL);
            if (brief == null && grayHit) {
                brief = coreGateway.promptTemplateLatestByStatus(name, TEMPLATE_DOMAIN, STATUS_FULL);
            }
            if (brief == null || brief.content() == null || brief.content().isBlank()) {
                return java.util.Optional.empty();
            }
            if (pin) {
                try {
                    coreGateway.pinTemplate(taskId, brief.id());
                } catch (Exception e) {
                    log.warn("Prompt 模板钉住回填失败 taskId={} templateId={}（选中不回退，下轮重钉）: {}",
                            taskId, brief.id(), e.getMessage());
                }
            }
            return java.util.Optional.of(brief);
        } catch (Exception e) {
            log.warn("Prompt 模板选择链路异常 name={} projectId={}（降级内置默认，禁炸生成）: {}",
                    name, projectId, e.getMessage());
            return java.util.Optional.empty();
        }
    }


    
    public String render(String content, Map<String, String> vars) {
        String out = content;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            out = out.replace("{{" + entry.getKey() + "}}",
                    entry.getValue() == null ? "" : entry.getValue());
        }
        return out;
    }
}
