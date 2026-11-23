package com.sixbean.testpike.aiservice.gen.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.explore.domain.component.ExploreTaskTimeline;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExecTaskResultRemote;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;
import com.sixbean.testpike.common.util.ExploreEnvelopeContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Component
public class PageInspectBridge {

    private static final Logger log = LoggerFactory.getLogger(PageInspectBridge.class);

    
    public static final int SWITCH_ON = 1;

    /**
 * Truth-source row : factory ON —
 * an absent row or a hand-dirtied value keeps the probe as the default
 * stage2 link; only an explicit 0/false restores the legacy per-project
 * opt-in (column 1 projects keep their probe in both states, the old
 * behaviour never shrinks). Single source shared with the service call
 * site through {@link #enabledFor} (red line 6, no second rule).
 */
    public static final String KEY_TRUTH_SOURCE_ENABLED =
            "ai.gen.truth-source.ui-pageinspect";

    
    static final int ENV_TYPE_PRODUCTION = 4;

    
    static final int EXEC_STATUS_SUCCESS = 3;

    
    static final int PROMPT_SEGMENT_MAX_CHARS = 4000;

 
    public static final String SEGMENT_ANCHOR = "目标页可交互元素候选（已验证存在）：";

    private final CoreGateway coreGateway;
    private final ObjectMapper objectMapper;

    public PageInspectBridge(CoreGateway coreGateway, ObjectMapper objectMapper) {
        this.coreGateway = coreGateway;
        this.objectMapper = objectMapper;
    }

    /**
 * Injection gate, single source the probe is promoted
 * from per-project opt-in to the default stage2 link): while the
 * truth-source row reads on (absent row or dirty value = factory ON) every
 * project gets its page probe; an explicit 0/false restores the legacy
 * shape where only column-1 projects are probed. Live read, never cached
 * (K8 hot-update rule); a failing config read path is fail-soft = off.
 */
    public boolean enabledFor(long projectId) {
        if (SysConfigBooleans.parse(
                coreGateway.getSysConfig(KEY_TRUTH_SOURCE_ENABLED), true)) {
            return true;
        }
        try {
            CoreGateway.ProjectBrief project = coreGateway.getProject(projectId);
            return project != null
                    && Integer.valueOf(SWITCH_ON).equals(project.pageInspectEnabled());
        } catch (Exception e) {
            log.warn("G4 页面探查开关读路径失败 projectId={}: {}", projectId, e.getMessage());
            return false;
        }
    }

    
    public String inspectSegment(long projectId, long taskId) {
        CoreGateway.EnvBrief env = pickEnv(projectId, taskId);
        if (env == null) {
            return "";
        }
        try {
            long execTaskId = coreGateway.submitExploreActStandalone(projectId,
                    envelopeJson(env.baseUrl()));
            if (execTaskId <= 0) {
                log.warn("G4 探查提交返回空任务 ID projectId={} taskId={}", projectId, taskId);
                return "";
            }
            ExecTaskResultRemote task = coreGateway.pollExecTaskResult(execTaskId);
            String segment = candidatesSegment(task);
            log.info("G4 页面探查回收 projectId={} taskId={} execTaskId={} 段长={}",
                    projectId, taskId, execTaskId, segment.length());
            return segment;
        } catch (Exception e) {
            log.warn("G4 页面探查失败（fail-soft 不阻断生成）projectId={} taskId={}: {}",
                    projectId, taskId, e.getMessage());
            return "";
        }
    }

    
    CoreGateway.EnvBrief pickEnv(long projectId, long taskId) {
        try {
            List<CoreGateway.EnvBrief> envs = coreGateway.listProjectEnvs(projectId);
            return envs.stream()
                    .filter(en -> en != null && en.baseUrl() != null
                            && !en.baseUrl().isBlank()
                            && !Integer.valueOf(ENV_TYPE_PRODUCTION).equals(en.envType()))
                    .min(Comparator.comparing(CoreGateway.EnvBrief::id))
                    .orElse(null);
        } catch (Exception e) {
            log.warn("G4 探查环境清单读路径失败 projectId={} taskId={}: {}",
                    projectId, taskId, e.getMessage());
            return null;
        }
    }

    
    String envelopeJson(String url) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("kind", ExploreEnvelopeContract.EXPLORE_ACT_KIND);
        envelope.put("action", "element-candidates");
        envelope.put("url", url);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("探查信封组装失败", e);
        }
    }

    
    String candidatesSegment(ExecTaskResultRemote task) {
        if (task == null || task.status() != EXEC_STATUS_SUCCESS) {
            return "";
        }
        try {
            String detail = ExploreTaskTimeline.detailFor(task.stepsTimeline(),
                    "element-candidates");
            String candidates = candidatesText(detail);
            if (candidates == null) {
                return "";
            }
            return SEGMENT_ANCHOR + "\n" + candidates + "\n"
                    + "定位要求：steps/setup 中命中上述元素的动作必须原样复用候选 "
                    + "target（css/text 定位对象），禁止另猜新的定位符。\n";
        } catch (Exception e) {
            log.warn("G4 候选段组装失败（fail-soft）: {}", e.getMessage());
            return "";
        }
    }

    
    String candidatesText(String detailJson) {
        JsonNode payload = readJson(detailJson);
        if (payload == null) {
            return null;
        }
        JsonNode candidates = payload.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : candidates) {
            JsonNode target = c.path("target");
            String locator = target.has("css") ? "css=" + target.path("css").asText()
                    : target.has("text") ? "text=" + target.path("text").asText() : null;
            if (locator == null) {
                continue;
            }
            String text = c.path("text").asText("");
            sb.append("- <").append(c.path("tag").asText()).append("> ")
                    .append(text.isBlank() ? "" : "「" + text + "」 ")
                    .append(locator).append('\n');
            if (sb.length() > PROMPT_SEGMENT_MAX_CHARS) {
                sb.append("-（候选超出注入预算，后续已截断）\n");
                break;
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    
    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
