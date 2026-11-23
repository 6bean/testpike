package com.sixbean.testpike.aiservice.judge.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.judge.domain.component.EvalTargetAdapterRegistry;
import com.sixbean.testpike.aiservice.judge.domain.component.JudgeLlmVerdictParser;
import com.sixbean.testpike.aiservice.judge.domain.component.JudgePromptComposer;
import com.sixbean.testpike.aiservice.judge.domain.component.JudgeVerdict;
import com.sixbean.testpike.aiservice.judge.domain.service.JudgeOrchestrationService;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeArtifactGateway;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeCoreGateway;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeRecordWrite;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeRuleVerdictBrief;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeRunBrief;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeSampleBrief;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmAuditRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmConfigBrief;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmProviderFactory;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.JudgeSampleMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
public class JudgeOrchestrationServiceImpl implements JudgeOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(JudgeOrchestrationServiceImpl.class);

    
    private static final String AUDIT_OBJECT_TYPE = "judge_run";

    
    static final String REASON_ARTIFACT_KEY = "原始响应工件";

    private final JudgeCoreGateway judgeGateway;
    private final CoreGateway coreGateway;
    private final LlmProviderFactory providerFactory;
    private final JudgeArtifactGateway artifactGateway;
    private final JudgeLlmVerdictParser verdictParser;
    private final JudgePromptComposer promptComposer;
    private final EvalTargetAdapterRegistry targetRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JudgeOrchestrationServiceImpl(JudgeCoreGateway judgeGateway,
                                         CoreGateway coreGateway,
                                         LlmProviderFactory providerFactory,
                                         JudgeArtifactGateway artifactGateway,
                                         JudgeLlmVerdictParser verdictParser,
                                         JudgePromptComposer promptComposer,
                                         EvalTargetAdapterRegistry targetRegistry) {
        this.judgeGateway = judgeGateway;
        this.coreGateway = coreGateway;
        this.providerFactory = providerFactory;
        this.artifactGateway = artifactGateway;
        this.verdictParser = verdictParser;
        this.promptComposer = promptComposer;
        this.targetRegistry = targetRegistry;
    }

    @Override
    public void judge(JudgeSampleMessage message) {
        JudgeRunBrief run = judgeGateway.getRun(message.runId());
        if (run == null) {
            log.info("裁判任务丢弃：Run 不存在（陈旧消息）runId={} sampleId={}",
                    message.runId(), message.sampleId());
            return;
        }
        if (!java.util.Objects.equals(message.evalsetDigest(), run.evalsetDigest())) {
            auditReject("JUDGE_STALE_REJECT", run, message,
                    "评测集快照锚不一致：派发时=" + message.evalsetDigest()
                            + "，现锚=" + run.evalsetDigest() + "（样本版本已被替换，拒评防旧请求评新数据）");
            return;
        }
        if (run.status() == null || run.status() != 1) {
            auditReject("JUDGE_NOT_RUNNING", run, message,
                    "Run 非跑批中状态（status=" + run.status() + "）：重放/迟到消息丢弃");
            return;
        }
        JudgeSampleBrief sample = locateSample(run, message.sampleId());

        String evaluatedOutput = "external".equals(run.targetType())
                ? targetRegistry.resolve(run).fetchEvaluatedText(run, sample)
                : sample.expected();

        JudgeRuleVerdictBrief ruleVerdict = judgeGateway.ruleCheck(
                rulesOf(run.rubricSnapshot()), evaluatedOutput,
                String.join("\n", sample.referenceDocs() == null ? List.of() : sample.referenceDocs()));
        if (ruleVerdict != null && ruleVerdict.shortCircuited()) {
            judgeGateway.writeRecord(new JudgeRecordWrite(run.id(), sample.id(),
                    ruleVerdict.ruleScore(), nullSafeHits(ruleVerdict),
                    Map.of(), Map.of("裁判说明", "硬规则零分短路，未调用 LLM"), 0L, 0));
            return;
        }

        LlmConfigBrief config = resolveJudgeConfig(message.llmConfigId());
        List<String> dimensionNames = promptComposer.dimensionNames(run.rubricSnapshot());
        String evidenceText = evidenceText(evaluatedOutput, sample);
        LlmEndpointConfig endpoint = new LlmEndpointConfig(config.provider(),
                config.modelName(), config.apiKey(), config.baseUrl(),
                LlmEndpointConfig.SOURCE_CONFIG, config.promptLimitTokens(),
                config.id(), config.tokenizerFamily());
        ChatCompletionRequest request = ChatCompletionRequest.of(config.modelName(),
                promptComposer.compose(run.rubricSnapshot(), sample.input(),
                        evaluatedOutput, sample.referenceDocs()));

        long startedAt = System.currentTimeMillis();
        LlmChatResult first = providerFactory.chatFor(request, endpoint);
        JudgeVerdict verdict = verdictParser.parse(first.content(), dimensionNames, evidenceText);
        int tokens = first.promptTokens() + first.completionTokens();
        LlmChatResult second = null;
        if (verdict.llmScores().isEmpty() && !verdict.orphanDimensions().isEmpty()) {
            second = providerFactory.chatFor(request, endpoint);
            JudgeVerdict retried = verdictParser.parse(second.content(), dimensionNames, evidenceText);
            tokens += second.promptTokens() + second.completionTokens();
            if (retried.llmScores().isEmpty()) {
                auditReject("JUDGE_NO_EVIDENCE", run, message,
                        "两次裁判回包均为孤儿分（引用锚不在被评材料内），按规则层残存落分："
                                + "无据维度=" + retried.orphanDimensions());
            }
            verdict = retried;
        }
        long latencyMs = System.currentTimeMillis() - startedAt;
        Map<String, String> reasons = new LinkedHashMap<>(verdict.reasons());
        if (!verdict.orphanDimensions().isEmpty()) {
            reasons.put("判分无据剔除维度", String.join("、", verdict.orphanDimensions()));
        }
        String artifactKey = storeRawResponseArtifact(run.id(), sample.id(), config,
                first, second, tokens, latencyMs);
        if (artifactKey != null) {
            reasons.put(REASON_ARTIFACT_KEY, artifactKey);
        }
        judgeGateway.writeRecord(new JudgeRecordWrite(run.id(), sample.id(),
                ruleVerdict == null ? null : ruleVerdict.ruleScore(),
                nullSafeHits(ruleVerdict), verdict.llmScores(), reasons,
                latencyMs, tokens));
    }

    
    private String storeRawResponseArtifact(long runId, long sampleId, LlmConfigBrief config,
                                            LlmChatResult first, LlmChatResult second,
                                            int tokens, long latencyMs) {
        try {
            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("runId", runId);
            artifact.put("sampleId", sampleId);
            artifact.put("llmConfigId", config.id());
            artifact.put("provider", config.provider());
            artifact.put("model", config.modelName());
            artifact.put("tokens", tokens);
            artifact.put("latencyMs", latencyMs);
            artifact.put("firstContent", first.content());
            artifact.put("firstPromptTokens", first.promptTokens());
            artifact.put("firstCompletionTokens", first.completionTokens());
            if (second != null) {
                artifact.put("secondContent", second.content());
                artifact.put("secondPromptTokens", second.promptTokens());
                artifact.put("secondCompletionTokens", second.completionTokens());
            }
            byte[] payload = objectMapper.writeValueAsBytes(artifact);
            return artifactGateway.upload(runId, sampleId, payload);
        } catch (Exception e) {
            log.warn("裁判原始响应工件存证失败（落分主链不受影响，人审回读降级为无原文）："
                    + "runId={} sampleId={} 原因={}", runId, sampleId, e.getMessage());
            return null;
        }
    }

    private JudgeSampleBrief locateSample(JudgeRunBrief run, long sampleId) {
        List<JudgeSampleBrief> samples =
                judgeGateway.listSamples(run.evalsetName(), run.evalsetVersion());
        return samples.stream()
                .filter(s -> s.id() != null && s.id() == sampleId)
                .findFirst()
                .orElseThrow(() -> new ServiceException(
                        "评测样本在冻结版本中不存在（数据异常）：runId=" + run.id()
                                + " sampleId=" + sampleId + " 集=" + run.evalsetName()
                                + " v" + run.evalsetVersion()));
    }

    private LlmConfigBrief resolveJudgeConfig(long llmConfigId) {
        return coreGateway.listEnabledLlmConfigs().stream()
                .filter(c -> c.id() == llmConfigId)
                .findFirst()
                .orElseThrow(() -> new ServiceException(
                        "裁判模型引用无匹配的启用配置：llmConfigId=" + llmConfigId
                                + "（模型可能被停用/删除，请检查评测运行配置）"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rulesOf(Map<String, Object> rubricSnapshot) {
        Object rules = rubricSnapshot == null ? null : rubricSnapshot.get("rules");
        return rules instanceof List ? (List<Map<String, Object>>) rules : List.of();
    }

    
    private String evidenceText(String evaluatedOutput, JudgeSampleBrief sample) {
        StringBuilder text = new StringBuilder();
        if (evaluatedOutput != null) {
            text.append(evaluatedOutput).append('\n');
        }
        if (sample.referenceDocs() != null) {
            text.append(String.join("\n", sample.referenceDocs()));
        }
        return text.toString();
    }

    private List<String> nullSafeHits(JudgeRuleVerdictBrief verdict) {
        return verdict == null || verdict.hits() == null ? List.of() : verdict.hits();
    }

    private void auditReject(String action, JudgeRunBrief run, JudgeSampleMessage message,
                             String detail) {
        log.warn("裁判任务拒评 action={} runId={} sampleId={} detail={}",
                action, message.runId(), message.sampleId(), detail);
        String objectId = "run:" + run.id() + "/sample:" + message.sampleId();
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("evalsetDigestMessage", message.evalsetDigest());
        before.put("evalsetDigestRun", run.evalsetDigest());
        before.put("runStatus", run.status());
        coreGateway.writeAudit(new LlmAuditRequest(null, null, AUDIT_OBJECT_TYPE,
                objectId, action, before, Map.of("action", action, "detail", detail)));
    }
}
