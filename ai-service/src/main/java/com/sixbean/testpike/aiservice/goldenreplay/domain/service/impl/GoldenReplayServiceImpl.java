package com.sixbean.testpike.aiservice.goldenreplay.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.goldenreplay.api.dto.GoldenHeadReplayRequest;
import com.sixbean.testpike.aiservice.goldenreplay.api.dto.GoldenReplayRequest;
import com.sixbean.testpike.aiservice.goldenreplay.api.dto.GoldenReplayResult;
import com.sixbean.testpike.aiservice.goldenreplay.api.dto.GoldenRouteReplayRequest;
import com.sixbean.testpike.aiservice.goldenreplay.domain.component.GoldenPack;
import com.sixbean.testpike.aiservice.goldenreplay.domain.component.OutputDriftDigest;
import com.sixbean.testpike.aiservice.goldenreplay.domain.service.GoldenReplayService;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeCoreGateway;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeSampleBrief;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.common.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Golden-set replay executor (the replay side of the drift gate): reads the
 * domain's registered golden pack from the judge eval-set, runs every sample
 * through the two sides of one comparison axis, and lands the resulting pair
 * digests on the core drift ledger. The ledger re-derives the drift rate
 * server-side — this service never ships a verdict of its own, only
 * normalized digests.
 * <p>Three axes share one loop widened the mechanism without a
 * second replayer): the template axis (control vs candidate version of one
 * pinned model, the promotion-gate feed), the route axis (one released
 * template through two model routes, so a planned model switch is measured
 * before it ships) and the head axis (one template+model through the old and
 * the new assembler head, so an assembly-segment change is measured the same
 * way). On every axis the non-varied parts stay byte-identical between the
 * two sides — that is what makes the drift number mean the change.
 * <p>Refusal semantics (all BEFORE any model call, so a malformed comparison
 * burns no tokens): unknown domain mapping, empty pack, missing template row,
 * identical sides on any axis. Every refusal names the config key and the
 * page to fix it on.
 */
@Service
public class GoldenReplayServiceImpl implements GoldenReplayService {

    /** Single-source registry: template domain -> golden pack (judge eval-set). */
    public static final String KEY_PACK_MAPPING = "ai.behavior.golden-pack.mapping";
    /** Full-release row status (mirrors core PromptTemplateStatus.FULL). */
    static final int TEMPLATE_STATUS_FULL = 2;
    /** Head-axis control side: the legacy assembly, bare template body sent as-is. */
    public static final String HEAD_REF_LEGACY = "bare-body-head";
    /** Head-axis candidate side: the production shape, kernel invariant leads the body. */
    public static final String HEAD_REF_KERNEL = "kernel-prefixed-head";
    /** Wire literals mirroring the core ledger axis kinds (HTTP boundary, same mirroring precedent as TEMPLATE_STATUS_FULL). */
    private static final String AXIS_ROUTE = "route";
    private static final String AXIS_HEAD = "head";

    private static final Logger log = LoggerFactory.getLogger(GoldenReplayServiceImpl.class);

    private final CoreGateway coreGateway;
    private final JudgeCoreGateway judgeCoreGateway;
    private final LlmFallbackChain llmFallbackChain;
    private final ProjectLlmRouter projectLlmRouter;
    private final ObjectMapper objectMapper;

    public GoldenReplayServiceImpl(CoreGateway coreGateway, JudgeCoreGateway judgeCoreGateway,
                                   LlmFallbackChain llmFallbackChain, ProjectLlmRouter projectLlmRouter,
                                   ObjectMapper objectMapper) {
        this.coreGateway = coreGateway;
        this.judgeCoreGateway = judgeCoreGateway;
        this.llmFallbackChain = llmFallbackChain;
        this.projectLlmRouter = projectLlmRouter;
        this.objectMapper = objectMapper;
    }

    @Override
    public GoldenReplayResult replay(GoldenReplayRequest request) {
        String domain = requireText(request.domain(), "template domain");
        String name = requireText(request.templateName(), "template name");
        Long candidateId = request.candidateVersionId();
        if (candidateId == null || candidateId <= 0) {
            throw new ValidationException("candidate template version id must be a positive number");
        }
        if (candidateId.equals(request.controlVersionId())) {
            throw new ValidationException(
                    "candidate and control must be two different template versions (id=" + candidateId + ")");
        }
        CoreGateway.PromptTemplateBrief candidate = detailOrThrow(candidateId, "candidate");
        CoreGateway.PromptTemplateBrief control = resolveControl(request, name, domain);
        if (candidateId.equals(control.id())) {
            throw new ValidationException("the resolved full-release control version is the candidate itself "
                    + "(id=" + candidateId + ") — promote another version first or name a different control");
        }

        GoldenPack pack = resolvePack(domain);
        List<JudgeSampleBrief> samples = requireSamples(pack);
        long projectId = request.projectId() == null ? 0L : request.projectId();
        boolean hintPinned = isPinned(request.modelHint());
        String model = resolvePinnedModel(request.modelHint(), projectId);
        LlmFallbackNotice notice = new LlmFallbackNotice(null, request.projectId(),
                "黄金样本集回放漂移对比（" + domain + "）");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateDomain", domain);
        body.put("templateName", name);
        body.put("candidateVersionId", candidateId);
        body.put("controlVersionId", control.id());
        return runComparison(domain, name, pack, body, samples,
                input -> callTemplate(control.content(), input, model, projectId, notice),
                input -> callTemplate(candidate.content(), input, model, projectId, notice),
                "replay model=" + model + " pin=" + (hintPinned ? "explicit" : "project-routing")
                        + " samples=" + samples.size());
    }

    @Override
    public GoldenReplayResult replayRouteSwitch(GoldenRouteReplayRequest request) {
        String domain = requireText(request.domain(), "template domain");
        String name = requireText(request.templateName(), "template name");
        String controlModel = requireText(request.controlModel(), "control model route");
        String candidateModel = requireText(request.candidateModel(), "candidate model route");
        if (controlModel.equals(candidateModel)) {
            throw new ValidationException("route comparison needs two different model routes "
                    + "(both sides are \"" + candidateModel + "\")");
        }
        CoreGateway.PromptTemplateBrief released = requireReleasedRow(name, domain);
        GoldenPack pack = resolvePack(domain);
        List<JudgeSampleBrief> samples = requireSamples(pack);
        long projectId = request.projectId() == null ? 0L : request.projectId();
        LlmFallbackNotice notice = new LlmFallbackNotice(null, request.projectId(),
                "模型换档黄金回放漂移对比（" + domain + "）");
        // Control variable: one byte-identical production head on both sides.
        String head = BehaviorKernelAssembly.prefixKernelClause(
                released.content() == null ? "" : released.content());
        Map<String, Object> body = axisBody(domain, name, AXIS_ROUTE, controlModel, candidateModel);
        return runComparison(domain, name, pack, body, samples,
                input -> callHead(head, input, controlModel, projectId, notice),
                input -> callHead(head, input, candidateModel, projectId, notice),
                "route axis control=" + controlModel + " candidate=" + candidateModel
                        + " templateRow=" + released.id() + " samples=" + samples.size());
    }

    @Override
    public GoldenReplayResult replayHeadSwitch(GoldenHeadReplayRequest request) {
        String domain = requireText(request.domain(), "template domain");
        String name = requireText(request.templateName(), "template name");
        CoreGateway.PromptTemplateBrief released = requireReleasedRow(name, domain);
        GoldenPack pack = resolvePack(domain);
        List<JudgeSampleBrief> samples = requireSamples(pack);
        long projectId = request.projectId() == null ? 0L : request.projectId();
        boolean hintPinned = isPinned(request.modelHint());
        String model = resolvePinnedModel(request.modelHint(), projectId);
        LlmFallbackNotice notice = new LlmFallbackNotice(null, request.projectId(),
                "装配段变更黄金回放漂移对比（" + domain + "）");
        String content = released.content() == null ? "" : released.content();
        String legacyHead = content;
        String kernelHead = BehaviorKernelAssembly.prefixKernelClause(content);
        Map<String, Object> body = axisBody(domain, name, AXIS_HEAD, HEAD_REF_LEGACY, HEAD_REF_KERNEL);
        return runComparison(domain, name, pack, body, samples,
                input -> callHead(legacyHead, input, model, projectId, notice),
                input -> callHead(kernelHead, input, model, projectId, notice),
                "head axis model=" + model + " pin=" + (hintPinned ? "explicit" : "project-routing")
                        + " control=" + HEAD_REF_LEGACY + " candidate=" + HEAD_REF_KERNEL
                        + " samples=" + samples.size());
    }

    private CoreGateway.PromptTemplateBrief resolveControl(GoldenReplayRequest request,
                                                           String name, String domain) {
        Long controlId = request.controlVersionId();
        if (controlId != null && controlId > 0) {
            return detailOrThrow(controlId, "control");
        }
        return requireReleasedRow(name, domain);
    }

    /**
     * The family's current full-release row — the fixed template side of the
     * route and head axes (the template text is never the varied subject
     * there). A refusal names the family and stays before any model call.
     */
    private CoreGateway.PromptTemplateBrief requireReleasedRow(String name, String domain) {
        CoreGateway.PromptTemplateBrief released =
                coreGateway.promptTemplateLatestByStatus(name, domain, TEMPLATE_STATUS_FULL);
        if (released == null || released.id() == null) {
            throw new ValidationException("template family " + domain + "/" + name
                    + " has no full-release version to compare against — a golden replay needs a released control version");
        }
        return released;
    }

    private List<JudgeSampleBrief> requireSamples(GoldenPack pack) {
        List<JudgeSampleBrief> samples = judgeCoreGateway.listSamples(pack.setName(), pack.version());
        if (samples == null || samples.isEmpty()) {
            throw new ValidationException("golden pack " + pack.setName() + " version " + pack.version()
                    + " has no samples — freeze the sample set in the eval management page before replaying");
        }
        return samples;
    }

    private static boolean isPinned(String modelHint) {
        return modelHint != null && !modelHint.isBlank();
    }

    private String resolvePinnedModel(String modelHint, long projectId) {
        return isPinned(modelHint) ? modelHint.trim() : projectLlmRouter.route(projectId).modelName();
    }

    /** Non-template axis body: subjects live on the ref pair, never on template FKs. */
    private static Map<String, Object> axisBody(String domain, String name, String kind,
                                                String controlRef, String candidateRef) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateDomain", domain);
        body.put("templateName", name);
        body.put("comparisonKind", kind);
        body.put("controlRef", controlRef);
        body.put("candidateRef", candidateRef);
        return body;
    }

    /**
     * The single replay loop every axis shares (R40: no second replayer):
     * per sample it runs the two side-operators, normalizes both outputs to
     * digests and posts the pair list plus the axis-specific body fields to
     * the core ledger. The drifted count returned is only an echo — the
     * authoritative rate is re-derived server-side.
     */
    private GoldenReplayResult runComparison(String domain, String name, GoldenPack pack,
                                             Map<String, Object> body, List<JudgeSampleBrief> samples,
                                             Function<String, String> controlSide,
                                             Function<String, String> candidateSide, String note) {
        String snapshotDigest = OutputDriftDigest.packDigest(samples);
        List<Map<String, Object>> pairs = new ArrayList<>(samples.size());
        int drifted = 0;
        for (JudgeSampleBrief sample : samples) {
            String controlDigest = OutputDriftDigest.digestOf(controlSide.apply(sample.input()));
            String candidateDigest = OutputDriftDigest.digestOf(candidateSide.apply(sample.input()));
            if (!controlDigest.equals(candidateDigest)) {
                drifted++;
            }
            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("sampleKey", String.valueOf(sample.id()));
            pair.put("controlDigest", controlDigest);
            pair.put("candidateDigest", candidateDigest);
            pairs.add(pair);
        }
        body.put("packName", pack.setName());
        body.put("packVersion", pack.version());
        body.put("snapshotDigest", snapshotDigest);
        body.put("pairs", pairs);
        body.put("note", note);
        coreGateway.postGoldenDriftVerdict(body);
        log.info("golden replay landed kind={} domain={} name={} samples={} drifted={} digest={}",
                body.getOrDefault("comparisonKind", "template"), domain, name,
                samples.size(), drifted, snapshotDigest.substring(0, 12));
        return new GoldenReplayResult(domain, name, pack.setName(), pack.version(),
                snapshotDigest, samples.size(), drifted);
    }

    private CoreGateway.PromptTemplateBrief detailOrThrow(long templateVersionId, String side) {
        CoreGateway.PromptTemplateBrief row = coreGateway.promptTemplateDetail(templateVersionId);
        if (row == null || row.id() == null) {
            throw new ValidationException(side + " template version " + templateVersionId
                    + " no longer exists — re-check the version on the prompt template management page");
        }
        return row;
    }

    private GoldenPack resolvePack(String domain) {
        String setName = null;
        int version = 0;
        String raw = coreGateway.getSysConfig(KEY_PACK_MAPPING);
        if (raw != null && !raw.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(raw).path(domain);
                if (!node.isMissingNode() && !node.isNull()) {
                    setName = node.path("setName").asText(null);
                    version = node.path("version").asInt(0);
                }
            } catch (Exception parseFailure) {
                log.warn("golden pack mapping for key {} is not valid JSON, refusing replay: {}",
                        KEY_PACK_MAPPING, parseFailure.getMessage());
            }
        }
        if (setName == null || setName.isBlank() || version <= 0) {
            throw new ValidationException("域名 " + domain + " 尚未登记黄金样本包：请到 系统设置→参数设置，"
                    + "在参数 " + KEY_PACK_MAPPING + " 中补充 {\"" + domain
                    + "\":{\"setName\":\"评测集名称\",\"version\":版本号}} 后重试，保存后立即生效");
        }
        return new GoldenPack(setName.trim(), version);
    }

    /**
     * One version side of the template-axis comparison: the replayed version's
     * template body behind the shared kernel invariant (single-source head
     * from {@link BehaviorKernelAssembly}, so the drift verdict compares the
     * exact head shape production traffic sends — same clause, same cache
     * prefix, never a bare body) and the sample input as user turn.
     */
    private String callTemplate(String templateContent, String sampleInput, String model,
                                long projectId, LlmFallbackNotice notice) {
        String head = BehaviorKernelAssembly.prefixKernelClause(
                templateContent == null ? "" : templateContent);
        return callHead(head, sampleInput, model, projectId, notice);
    }

    /** One model call with a fully-assembled head and the sample input as user turn. */
    private String callHead(String head, String sampleInput, String model,
                            long projectId, LlmFallbackNotice notice) {
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", head),
                new ChatMessage("user", sampleInput == null ? "" : sampleInput));
        LlmChatResult result = llmFallbackChain.chat(
                new ChatCompletionRequest(model, messages, null, null), projectId, notice, true);
        return result == null ? null : result.content();
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(label + " is required for a golden replay");
        }
        return value.trim();
    }
}
